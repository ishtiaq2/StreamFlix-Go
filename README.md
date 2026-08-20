# StreamFlix-7 + Go

**A hands-on systems-architecture playground.** Nine small, real, runnable services —
Java, Python, and Go — wired together with Podman so that ideas like "load balancer,"
"event-driven architecture," "goroutine," and "role-based access control" stop being
words in an article and become things you can build, break, and watch recover on your
own machine.

This repo is written as a **teaching curriculum first, a demo second.** Every service
is small on purpose. The point isn't to build a real streaming platform — it's to build
just enough of one, honestly, that the architecture patterns real platforms use
(Netflix chief among them) become intuitive because you built a working miniature of
each one yourself.

No prior container or Go experience required. Some comfort with the command line and
at least one programming language is assumed.

---

## What you'll actually learn

- **Systems architecture** — why a real platform splits into an edge layer, independent
  services, a database of record, an events pipeline, and a monitoring interface,
  instead of one giant program.
- **Containers**, using Podman — images, multi-stage builds, networks, compose files,
  and the process/namespace concepts underneath all of it.
- **Java web services** — an embedded Jetty server with a Jersey (JAX-RS) REST API,
  built without a framework hiding the pieces from you.
- **Python web services** — FastAPI, used the way real backend teams use it.
- **Go**, from zero — goroutines, channels, worker pools, `context` cancellation,
  interfaces as a design tool, and a real `go test` suite, all built from the standard
  library alone.
- **Event-driven architecture** — the difference between a service calling another
  service directly and a service publishing an event that nothing has to be listening
  for yet.
- **Identity and authorization** — what a token actually is, how role-based access
  control works, and why "authenticated" and "authorized" are two different questions.
- **Chaos engineering** — deliberately breaking something and watching the rest of the
  system detect, alarm on, and recover from it.

---

## The ten containers

| # | Container | Language/stack | What it teaches |
|---|---|---|---|
| 1 | `database` | PostgreSQL | Relational schema design; a shared source of truth |
| 2 | `core-engine` | Java, embedded Jetty + Jersey | Building a REST API without a framework hiding the HTTP layer from you |
| 3 | `catalog-api` | Python, FastAPI | A second web-framework style, and calling one service from another |
| 4 | `web-ui` | HTML/JS + nginx | The customer-facing edge of the system |
| 5 | `edge-lb` | nginx | Load balancing and the "one front door" principle |
| 6 | `chaos-agent` | Python | Deliberate fault injection — Chaos-Monkey-style resilience testing |
| 7 | `northbound` | Python, FastAPI | An interface exposed *outward*, decoupled from internals |
| 8 | `device-gateway` | **Go** | Goroutines, channels, worker pools, graceful shutdown, event-driven pub/sub |
| 9 | `auth-service` | **Go** | Identity, tokens, role-based access control, table-driven testing |
| — | `redis` | Redis | The event broker `device-gateway` publishes onto |

---

## Architecture

```
                              customer's browser
                                     │
                                     ▼
                        ┌──────────────────────────┐
                        │   web-ui (nginx+static)   │
                        └────────────┬─────────────┘
                                     │
                                     ▼
                        ┌──────────────────────────┐
                        │  edge-lb (nginx)          │  :8080
                        │  single front door        │
                        └────────────┬─────────────┘
                                     │
                                     ▼
                        ┌──────────────────────────┐
                        │  catalog-api (FastAPI)    │
                        └──────────┬───────────────┘
                                   │ events (HTTP)
                                   ▼
                     ┌────────────────────────────────────┐
                     │  core-engine (Jetty + Jersey)       │  :8981
                     │  /events   /alarms                  │
                     └───────────────┬────────────────────┘
                                     │ JDBC
                                     ▼
                        ┌──────────────────────────┐
                        │  database (PostgreSQL)    │  :5432
                        └────────────┬─────────────┘
                                     ▲
                ┌────────────────────┼─────────────────────┐
                │                    │                      │
     ┌────────────────┐   ┌──────────────────┐   ┌────────────────────┐
     │ chaos-agent     │   │ northbound       │   │ auth-service (Go)  │  :8083
     │ breaks things   │   │ /status /metrics │   │ identity, roles,   │
     │ on purpose      │   │ external polling │   │ permissions        │
     └─────────────────┘   └──────────────────┘   └─────────────────────┘

     ┌───────────────────────────────────────────────────────────────┐
     │  device-gateway (Go)                            :8082          │
     │  12 simulated devices, each its own goroutine                  │
     │       │ (Go channel)                                           │
     │       ▼                                                        │
     │  4-worker pool ──PUBLISH──▶  redis :6379  (event bus)          │
     └───────────────────────────────────────────────────────────────┘
```

All ten containers share one Podman network (`streamflix-net`) and reach each other by
service name.

---

## Repository layout

```
.
├── README.md
├── docs/
│   ├── GLOSSARY.md
│   ├── LEARNING-PATH.md
│   ├── 01-BASE-STACK-GUIDE.md
│   ├── 02-GO-DEVICE-GATEWAY-GUIDE.md
│   ├── 03-GO-AUTH-SERVICE-GUIDE.md
│   └── ADR-0001-identity-data-store.md
└── streamflix-stack/
    ├── podman-compose.yml
    ├── database/
    ├── core-engine/        (Java)
    ├── catalog-api/        (Python)
    ├── web-ui/
    ├── edge-lb/
    ├── chaos-agent/        (Python)
    ├── northbound/         (Python)
    ├── device-gateway/     (Go)
    └── auth-service/       (Go)
```

## Prerequisites

- Podman + `podman-compose` (`pip install podman-compose --break-system-packages`)
- Nothing else — every language toolchain (Maven/Java, Python, Go) runs *inside* the
  containers via multi-stage Dockerfiles. You don't need Java, Python, or Go installed
  on your host to build and run this.

## Quickstart

```bash
git clone <your-repo-url>
cd streamflix-stack
podman-compose up -d --build
podman-compose logs -f core-engine catalog-api device-gateway auth-service
```

Once `database` and `core-engine` report healthy:

```bash
curl -I http://localhost:8080/                 # web-ui, through edge-lb
curl http://localhost:8080/api/titles           # catalog-api, through edge-lb
curl http://localhost:8981/alarms               # core-engine directly
curl http://localhost:9000/status               # northbound status feed
curl http://localhost:8082/devices/status       # device-gateway's device fleet
curl -X POST http://localhost:8083/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@streamflix.local","password":"changeme"}'   # auth-service
```

Or just open `http://localhost:8080` in a browser.

## Where to start

Don't start with the code — start with `docs/LEARNING-PATH.md`. It lays out the
recommended reading/building order and tells you which guide to open first depending
on what you already know.

## What's genuinely "real life" here, and what's simplified

**Real patterns:** an edge load balancer in front of independently deployable
services; a dedicated events/alarms pipeline decoupled from the services that emit
events; chaos engineering as its own component; a northbound interface so external
tooling never touches the database or internal services directly; an event bus
(Redis Pub/Sub) decoupling publishers from subscribers; token-based identity and
role-based access control gating admin actions.

**Deliberately simplified:** one Postgres instance instead of a distributed
datastore; no TLS and only basic auth; no real video encoding, CDN, or streaming
protocol — "playback" is a database row and an event; passwords hashed with plain
SHA-256 rather than bcrypt/argon2; tokens can't be revoked before they expire;
`device-gateway`'s Redis client is hand-rolled (RESP protocol) for teaching purposes
rather than using a production client library.

## Security notes

- No TLS, and default/weak passwords sit in plain environment variables — fine for a
  local learning exercise, not for anything you expose beyond your own machine.
- `chaos-agent`'s SSH fault-injection path is documented but intentionally not wired
  up by default — see `docs/01-BASE-STACK-GUIDE.md`.
- Never commit real credentials to version control if you adapt this beyond a lab.

## License

Add your license of choice here (e.g. MIT) before publishing.
