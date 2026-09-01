# GraphVault — Netflix's Real Java Architecture, in Miniature

A hands-on learning project that reproduces the *specific* architecture described in Paul
Bakker's (Netflix, Java Platform team) "How Netflix Uses Java" talk and the ByteByteGo
deep-dive on it — federated GraphQL at the edge, Domain Graph Services (DGS) as the backend
unit of deployment, gRPC for internal service-to-service calls, generational ZGC, and Java
virtual threads — built to actually run on your own Linux machine with **Podman**.

This is a different project from an earlier "Netflix clone" you may have seen built the classic
way (Eureka + Spring Cloud Gateway + Zuul-style routing). That older pattern is genuine Netflix
history — it's literally where Eureka, Ribbon, Hystrix, and Zuul came from — but it is **not**
what current Netflix engineering describes running today. GraphVault is the newer, more
accurate picture. See [`docs/ARCHITECTURE_VS_BLOG.md`](docs/ARCHITECTURE_VS_BLOG.md) for a
direct, line-by-line comparison against the source material, including everything this project
simplifies.

## What this reproduces, concretely

| From the talk/blog | In this repo |
|---|---|
| "A federated GraphQL platform connecting client apps to dozens of Java backend services" | `graphql-gateway` — one public GraphQL endpoint fanning out to 3 subgraphs |
| "Every Domain Graph Service (DGS) at Netflix is a Spring Boot application" | `titles-dgs`, `artwork-dgs`, `availability-dgs` — real Netflix DGS Framework services |
| "Inside the backend, services communicate over gRPC" | `artwork-dgs` calls `asset-service` over gRPC + Protocol Buffers |
| "Now default to ZGC... spend more CPU to lower pause times" | Every JVM in this repo launches with `-XX:+UseZGC -XX:+ZGenerational` |
| "Spring Boot–based services automatically benefit from parallel execution" (virtual threads) | `spring.threads.virtual.enabled: true` everywhere, plus an explicit virtual-thread fan-out in the gateway |
| "What's not Java: ...Python for data science" | Out of scope here — this repo stays 100% Java on purpose, see the SoundVault project in this series for a polyglot example instead |

## Architecture

```
                    ┌─────────────────────┐
                    │   GraphiQL (you)     │
                    └──────────┬───────────┘
                               │ HTTP POST /graphql
                               ▼
                    ┌─────────────────────┐
                    │      nginx-lb        │   external load balancer (port 80)
                    └──────────┬───────────┘
                ┌──────────────┴──────────────┐
                ▼                             ▼
     ┌─────────────────────┐       ┌─────────────────────┐
     │ graphql-gateway-1     │       │ graphql-gateway-2     │   Spring Boot, virtual threads,
     │ (federation-lite)     │       │ (federation-lite)     │   graphql-java AST parser
     └──────────┬────────────┘       └──────────┬────────────┘
                │  parallel fan-out (virtual threads) per query
     ┌──────────┼──────────────────────┬─────────────────────┐
     ▼          ▼                      ▼                     
┌───────────┐ ┌────────────┐   ┌─────────────────────┐
│ titles-dgs │ │artwork-dgs │   │ availability-dgs     │   3 Domain Graph Services
│ (H2)       │ │            │   │ (H2)                  │   (Netflix DGS Framework)
└───────────┘ └─────┬──────┘   └─────────────────────┘
                     │ gRPC (Protocol Buffers)
                     ▼
              ┌─────────────┐
              │ asset-service│   plain gRPC backend, no GraphQL surface
              └─────────────┘
```

## Services in this repo

| Service | Port | Role |
|---|---|---|
| `nginx-lb` | 80 | External load balancer in front of the gateway replicas |
| `graphql-gateway` (×2) | 8080 / 8180 | The single public GraphQL entry point; federation-lite fan-out |
| `titles-dgs` | 8081 | DGS owning `Title` (movie/show metadata) |
| `artwork-dgs` | 8082 | DGS owning `Artwork`; calls `asset-service` internally over gRPC |
| `availability-dgs` | 8083 | DGS owning per-region `Availability` |
| `asset-service` | 9090 | Plain gRPC backend simulating signed CDN artwork URLs |

## Prerequisites

- Linux with Podman ≥ 4.x and `podman-compose`
- Java 21 JDK + Maven 3.9+ (only needed for local builds outside containers)
- Real internet access to Maven Central during the first build (for Spring Boot, DGS Framework,
  and — importantly — for `protobuf-maven-plugin` to download a matching `protoc` binary the
  first time `asset-service` or `artwork-dgs` builds)

## Quickstart

```bash
git clone <your-fork-url> graphvault && cd graphvault
podman-compose -f podman-compose.yml up --build
```

Then open **http://localhost/graphiql.html** for an interactive GraphQL IDE pointed at the
load-balanced gateway, and try the default query — it fans out to all three DGSs in one round
trip. You can also hit any single DGS's own GraphiQL directly while it's running, e.g.
**http://localhost:8081/graphiql**, to see it in isolation before the gateway stitches it in.

## What this project intentionally simplifies

This is the short version — the full, honest list (with reasoning for each) is in
[`docs/ARCHITECTURE_VS_BLOG.md`](docs/ARCHITECTURE_VS_BLOG.md):

- **No real Apollo Federation.** `graphql-gateway` hand-rolls a "federation-lite" that can only
  route distinct top-level fields to distinct subgraphs — it cannot stitch a field like
  `artwork` directly onto `Title` the way real federation's `@key`/`_entities` protocol does.
- **No service mesh / no Eureka.** Container-name DNS (Podman's built-in) stands in for both
  Netflix's real internal proxy mesh (ProxyD) and this series' earlier Eureka-based projects.
- **No TLS anywhere.** Real Netflix's internal gRPC traffic runs over mTLS via that mesh.
- **The `.proto` contract is copy-pasted**, not served from a shared schema registry.
- **H2 in-memory databases**, not Cassandra/EVCache/whatever backs the real services.

## Docs

- [`docs/ARCHITECTURE_VS_BLOG.md`](docs/ARCHITECTURE_VS_BLOG.md) — line-by-line comparison
  against the source talk/blog, and the full simplifications list
- [`docs/STEP_BY_STEP_GUIDE.md`](docs/STEP_BY_STEP_GUIDE.md) — build and run everything, with
  example queries and troubleshooting
- [`docs/JAVA_SPRING_BOOT_EXPLAINED.md`](docs/JAVA_SPRING_BOOT_EXPLAINED.md) — the Java/Spring
  Boot/DGS/gRPC code explained in depth

## License

MIT — see `LICENSE`.
