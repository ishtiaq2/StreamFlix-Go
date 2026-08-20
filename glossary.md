# Glossary

Plain-language definitions for every term this repo assumes you'll pick up, grouped
by the area of the stack that introduces it. Written for someone who's never met the
term before — if a definition still feels circular, that's worth flagging as a gap.

## Containers & architecture

- **Container** — a process (or small group of processes) running with its own
  isolated filesystem, network, and process namespace, but sharing the host's kernel.
  Lighter than a full virtual machine, because it doesn't run a second operating
  system inside it.
- **Image** — the frozen, buildable blueprint a container is started from (think:
  "the recipe," where the container is "the meal").
- **Dockerfile** — the instructions for building an image, one layer at a time.
- **Multi-stage build** — a Dockerfile with more than one `FROM` line: an early stage
  compiles the code with a full toolchain (Maven, Go compiler, etc.), and only the
  compiled result is copied into a small final image. Keeps the shipped image free of
  build tools it doesn't need at runtime.
- **Compose file** (`podman-compose.yml`) — one file describing every container in a
  stack, how they're networked, and what each depends on, so the whole system starts
  with one command instead of ten.
- **Edge / load balancer** — the single entry point traffic hits before being routed
  to whichever backend instance should handle it. `edge-lb` (nginx) plays this role
  here.
- **Northbound interface** — an interface exposed *upward*, to something that
  monitors or manages the system from outside (a dashboard, an ops team). The
  opposite is *southbound*: this system talking down to things it manages.
- **Reduction key** — a value events are grouped by so that ten identical failures
  collapse into one alarm instead of ten separate ones. `core-engine`'s
  `uei + streamId` combination is this project's reduction key.

## Java / core-engine

- **Jetty** — an embeddable Java web server; "embedded" means it runs as a library
  inside your own `main()` method rather than as separate software you deploy your
  code into.
- **Jersey** — the reference implementation of **JAX-RS**, the Java standard for
  building REST APIs using annotations like `@Path` and `@GET`.
- **Servlet** — the underlying Java API Jetty and Jersey are both built on top of; a
  servlet is a Java object that handles HTTP requests and produces HTTP responses.

## Python / catalog-api, chaos-agent, northbound

- **FastAPI** — a Python web framework for building REST APIs, using type hints to
  generate request/response validation automatically.
- **Uvicorn** — the ASGI server that actually runs a FastAPI application.

## Go / device-gateway, auth-service

- **Goroutine** — a lightweight, independently running function, started with the
  `go` keyword. Far cheaper than an OS thread (kilobytes of stack, not megabytes),
  which is why "one goroutine per device" scales to thousands without strain.
- **Channel** — a typed, thread-safe queue used to pass values between goroutines.
  Go's own guidance is "don't communicate by sharing memory; share memory by
  communicating" — channels are how you do the latter.
- **Buffered channel** — a channel with room to hold a fixed number of values before
  a send blocks, letting producers get slightly ahead of consumers without stalling.
- **`select`** — a statement that waits on multiple channel operations at once and
  proceeds with whichever is ready first. The standard way to make a goroutine
  respond to "the next event" and "a cancellation signal" simultaneously.
- **Worker pool** — a fixed number of goroutines all reading from the same channel,
  used to cap how much work runs concurrently rather than spawning unlimited
  goroutines for unlimited work.
- **`context.Context`** — Go's mechanism for propagating cancellation (and
  deadlines) through a whole tree of function calls and goroutines from one place.
- **`sync.WaitGroup`** — a counter a goroutine can `Add()` to and `Done()` when
  finished, so another goroutine can `Wait()` until all of them are done.
- **`sync.Mutex` / `sync.RWMutex`** — locks protecting data that multiple goroutines
  read and write concurrently. `RWMutex` allows many simultaneous readers but only
  one writer at a time.
- **RESP** — REdis Serialization Protocol, the plain-text-ish wire format Redis
  clients and servers speak. `device-gateway` speaks a minimal version of it by hand
  instead of using a client library, as a teaching exercise.
- **Pub/Sub (Publish/Subscribe)** — a messaging pattern where a publisher sends a
  message to a named channel/topic without knowing who (if anyone) is listening, and
  subscribers receive messages from a channel without knowing who published them.
  Decouples the two sides completely.
- **JWT-shaped token** — a token made of three base64url pieces —
  `header.payload.signature` — where the signature proves the payload hasn't been
  tampered with. `auth-service`'s tokens follow this shape, hand-built with HMAC
  instead of a library, to make the mechanism visible.
- **HMAC** — a way to produce a short, fixed-length "signature" of some data using a
  secret key, such that only someone holding the same key can verify (or forge) it.
- **RBAC (Role-Based Access Control)** — an authorization model where permissions are
  granted to *roles* (e.g. `admin`, `viewer`), and users are granted *roles*, rather
  than permissions being assigned to individual users directly.
- **Authentication vs. authorization** — authentication answers "who are you";
  authorization answers "what are you allowed to do." A valid token proves
  authentication; checking that token's role against a required permission is
  authorization. They're separate checks, done in that order.
- **Interface (Go)** — a set of method signatures with no implementation attached.
  Anything implementing those methods satisfies the interface automatically. Used in
  `auth-service`'s `UserStore` to let the storage backend change without touching any
  code that depends on it.
- **Table-driven test** — a Go testing style where a slice of `{input, expected
  output}` cases is looped over in one test function, instead of writing one test
  function per case.
