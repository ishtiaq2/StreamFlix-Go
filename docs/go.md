# StreamFlix-7, Phase 2 — Go, Concurrency, and an Event Bus

**Goal:** close the two biggest gaps from `CAREER-GAP-ANALYSIS.md` — no Go code, and
no message-queue/event-driven component — with one new piece: an 8th container,
`device-gateway`, written in Go, that simulates a fleet of devices and publishes
their telemetry onto a Redis Pub/Sub channel instead of calling another service
directly over REST.

Why this one addition covers so much ground:

- It's your first real Go program → **Role 1's hard requirement.**
- It's built entirely from goroutines, channels, a worker pool, and `context`
  cancellation → **Role 1's "concurrent and asynchronous programming."**
- It talks to Redis, which plays two roles at once: a **Pub/Sub broker** (the same
  *shape* as GCP Pub/Sub — publisher, topic/channel, decoupled subscriber) and a
  **NoSQL store** if you extend it later → covers a "nice to have" on **both roles**.
- It's one more independently deployable service behind the edge layer → reinforces
  the **distributed systems / microservices** story you already have from
  StreamFlix-7.

---

## Step 1 — Understand what changes architecturally

Today, StreamFlix-7 is entirely **synchronous**: `catalog-api` calls `core-engine`
directly over HTTP and waits for a response. That's simple, but it's not how
"telemetry from a fleet of devices" usually works in the real world — devices don't
wait for an answer, and nothing downstream should have to be listening at the exact
moment a device sends data.

`device-gateway` introduces the **event-driven** shape instead:

```
      12 simulated devices                     4 worker goroutines
      (one goroutine each)                     (a fixed-size pool)
             │                                          │
             │  buffered channel (in-process queue)     │
             └──────────────────►  events  ─────────────┘
                                                          │
                                                          ▼
                                              PUBLISH device-events
                                                          │
                                                          ▼
                                              ┌─────────────────────┐
                                              │   redis (broker)    │
                                              └─────────────────────┘
```

Notice there are **two** queues here, at two different layers, and interviewers care
about the distinction:

1. The **Go channel** (`events`) is an in-process queue connecting goroutines inside
   one binary. It never leaves the container.
2. **Redis Pub/Sub** is a cross-process/cross-service queue. Any other container on
   `streamflix-net` — today or in the future — can subscribe to `device-events`
   without `device-gateway` knowing or caring who's listening. That decoupling is the
   entire point of an event bus, and it's exactly what GCP Pub/Sub gives you at cloud
   scale.

---

## Step 2 — Read the domain types first

Open `device-gateway/cmd/gateway/main.go`. Before any concurrency, look at the plain
data:

- `TelemetryEvent` — what a device sends: an ID, a kind, a value, a timestamp.
- `DeviceStatus` / `Registry` — what the *gateway itself* remembers about each
  device so `/devices/status` has something to report.

`Registry` is guarded by a `sync.RWMutex`. This is the first concurrency concept in
the file: **multiple goroutines will read and write this map at the same time**, and
a plain Go map is not safe for that. `RWMutex` specifically (rather than a plain
`Mutex`) lets many HTTP requests read the snapshot in parallel, while a worker
writing a new event briefly locks everyone else out. This is a very common
interview question: *"how do you protect shared state without serializing every
request?"*

---

## Step 3 — The producers: one goroutine per device

`simulateDevice` is the function that runs once per simulated device. The key line
is the `select` inside the `for` loop:

```go
select {
case <-ctx.Done():
    return
case t := <-ticker.C:
    ...
}
```

`select` is Go's way of waiting on multiple channels at once and reacting to
whichever is ready first. Here it means: *"wait for either the next tick, or a
shutdown signal — whichever happens first."* This is the idiomatic way to make a
goroutine cancellable, and it's a pattern you'll be asked to reproduce in almost any
Go interview.

Twelve of these goroutines start in `main()`:

```go
for i := 0; i < numDevices; i++ {
    producers.Add(1)
    go simulateDevice(ctx, fmt.Sprintf("device-%03d", i), interval, events, &producers)
}
```

`sync.WaitGroup` (`producers`) is how `main()` later knows when all twelve have
actually finished, rather than just assuming they have.

---

## Step 4 — The consumers: a bounded worker pool

If every device goroutine also did its own network I/O, twelve devices are fine —
but the same pattern with ten thousand devices would open ten thousand simultaneous
Redis connections. Real systems cap that with a **worker pool**: a fixed number of
goroutines (`numWorkers`, default 4) all reading from the *same* channel:

```go
for i := 0; i < numWorkers; i++ {
    workers.Add(1)
    go worker(i, events, registry, redisAddr, &workers)
}
```

`for event := range in` inside `worker` is the consumer side: it keeps pulling
events until the channel is **closed**, not just empty. That distinction (closed vs.
empty) is another classic interview trip-up — closing a channel is a broadcast
signal to every reader that no more values are coming; it is not the same as the
channel having zero items in it right now.

---

## Step 5 — Why the channel is closed the way it is

```go
go func() {
    producers.Wait()
    close(events)
}()
```

This tiny goroutine exists to solve one problem: **only one goroutine may ever close
a channel, and it must be certain nothing will write to it afterward.** If any of the
twelve producers tried to write to `events` after it was closed, the program would
panic. So instead of closing it directly in `main()`, a dedicated goroutine waits for
every producer to finish (`producers.Wait()`) and *then* closes the channel — at
which point every worker's `for range` loop exits naturally and `workers.Wait()` in
`main()` can return.

This three-step handshake — producers finish → channel closes → consumers drain and
exit — is the standard Go shutdown pattern. It's worth being able to draw on a
whiteboard.

---

## Step 6 — Talking to Redis by hand (RESP)

`publishToRedis` doesn't use a Redis client library — it opens a raw TCP connection
and writes the **RESP** protocol directly:

```
*3\r\n$7\r\nPUBLISH\r\n$13\r\ndevice-events\r\n$42\r\n{...json...}\r\n
```

Read it as: *"an array of 3 elements: the string `PUBLISH`, the string
`device-events`, the string `<payload>`."* Redis replies with `:<n>\r\n`, where `n`
is how many subscribers received the message.

This is deliberately low-level. You could pull in `github.com/redis/go-redis/v9` in
five minutes and get the same behavior — and in real production code, you should,
rather than hand-rolling wire protocols — but writing the raw version once is one of
the fastest ways to actually understand what a "client library" is a wrapper *around*,
which directly serves the job posting's "good understanding of networking and
communication between systems."

---

## Step 7 — Graceful shutdown, end to end

```go
ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
```

This one line is what makes `podman stop device-gateway` behave the same as hitting
Ctrl+C: both deliver `SIGTERM`/`SIGINT`, and `signal.NotifyContext` turns that OS
signal into `ctx.Done()` firing — the same cancellation signal every producer
goroutine is already watching for in its `select`. That's why a single `ctx` created
once in `main()` is enough to cleanly stop twelve device goroutines, drain the
channel through four workers, and shut down the HTTP server, all in the right order.

---

## Step 8 — Wire it into the stack

Add two services to `streamflix-stack/podman-compose.yml` (alongside the existing
seven):

```yaml
  redis:
    image: docker.io/library/redis:7-alpine
    networks: [streamflix-net]
    ports:
      - "6379:6379"

  device-gateway:
    build: ./device-gateway
    networks: [streamflix-net]
    environment:
      NUM_DEVICES: "12"
      NUM_WORKERS: "4"
      REDIS_ADDR: "redis:6379"
      EVENT_INTERVAL_MS: "2000"
    ports:
      - "8082:8082"
    depends_on:
      - redis
```

Copy the `device-gateway/` folder from this Phase 2 bundle into
`streamflix-stack/device-gateway/` so it sits next to `core-engine/` and
`catalog-api/`.

---

## Step 9 — Build, run, and watch it work

```bash
cd streamflix-stack
podman-compose up -d --build redis device-gateway
podman-compose logs -f device-gateway
```

You should see a log line every 2 seconds per device batch as workers publish to
Redis. Check the gateway's own view of the fleet:

```bash
curl http://localhost:8082/devices/status | jq
```

To *see* the Pub/Sub side working, subscribe from a second terminal using Redis's
own CLI inside the container:

```bash
podman exec -it <redis-container-name> redis-cli SUBSCRIBE device-events
```

You'll see the same JSON payloads `device-gateway` is publishing arrive live — proof
that the publisher and this subscriber have no direct connection to each other at
all, only a shared channel name.

Stop it cleanly and confirm the graceful shutdown path:

```bash
podman-compose stop device-gateway
podman-compose logs device-gateway   # look for "shutdown signal received" / "stopped cleanly"
```

---

## Step 10 — What to say out loud in an interview

For **Role 1 (Go/IoT)**, be ready to explain, unprompted:

- Why goroutines instead of OS threads for "one per device" (cheap stacks, cheap
  context switches, Go runtime multiplexes them onto OS threads for you).
- The producer/worker-pool/closer pattern from Steps 4–5, and *why* closing a
  channel from the wrong place panics.
- `select` for cancellable loops, and `context.Context` for propagating shutdown
  through a whole goroutine tree from one signal.
- That this is the same "distributed, event-driven, decoupled services" story as
  the rest of StreamFlix-7 — `device-gateway` doesn't know or care what (if
  anything) is subscribed to `device-events`, same as `core-engine` doesn't know
  what's polling the northbound API.

For **Role 2 (Python/GCP)**, be ready to translate this into GCP vocabulary even
though you built it locally:

- Redis channel `device-events` ≈ a **Pub/Sub topic**.
- The worker pool publishing ≈ a **publisher client**.
- A separate consumer subscribing (Step 9) ≈ a **Pub/Sub subscription** with a pull
  or push consumer.
- The decoupling benefit is identical: publishers and subscribers scale
  independently and don't need to know about each other.

---

## Where this leaves you

- **Closed:** Go, concurrent/async programming, message-queue/event-driven
  architecture, one more networking-protocol example (RESP).
- **Still open** (see `CAREER-GAP-ANALYSIS.md`, items 4–7): NoSQL as an actual data
  store (Redis is already in the stack — using it for more than pub/sub is a short
  follow-on), a CI/CD pipeline, a Vertex-AI-style feature on `catalog-api`, and Solr.

When you're ready, say which of those you want next and it'll get the same
treatment: working code plus a step-by-step explanation of what each piece is doing
and why.