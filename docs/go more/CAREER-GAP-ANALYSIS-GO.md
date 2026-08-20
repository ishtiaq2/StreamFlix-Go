# Career Gap Analysis — Go Roles vs. StreamFlix-7 + auth-service

Two roles this time, given as responsibility lists rather than full postings. Calling
them **Role A** (connected-devices backend) and **Role B** (identity & access,
AWS-based) so the tables below can reference them directly.

**Role A — Connected-Devices Backend Engineer (Go)**
- Designing, developing and maintaining backend services using Go
- Developing APIs and integrations between backend systems, applications and connected
  devices
- Building scalable and maintainable services for a growing product ecosystem
- Working with data storage, communication protocols and distributed systems
- Writing tests and ensuring high code quality throughout the development process
- Troubleshooting and resolving complex technical issues
- Participating in architectural discussions, code reviews and technical
  decision-making
- Collaborating with frontend, firmware, product and other engineering teams
- Improving system performance, reliability and maintainability
- Contributing to the continuous development of engineering practices and tooling

**Role B — Identity & Access Backend Engineer (Go, AWS)**
- Design and develop backend services in Go
- Build systems for identity, authorization, roles, and permissions
- Help define what data should be persisted in AWS-based cloud services
- Improve systems that manage access across devices, networks, and admin platforms
- Work closely with product leadership and senior engineers on new technical direction
- Contribute to architecture decisions with a pragmatic, product-first mindset
- Collaborate cross-functionally with cloud, mobile, web, and embedded-adjacent teams
- Continuously prioritize, reduce risk, and make fast engineering decisions in a
  dynamic environment

---

## What StreamFlix-7 + Phase 2 (`device-gateway`) already covers

| Item | Source | Covers |
|---|---|---|
| Go backend service, written from scratch | `device-gateway` | Role A "designing/developing backend services in Go"; Role B "design and develop backend services in Go" |
| REST API design | `core-engine` (Jersey), `catalog-api`, `device-gateway`'s `/devices/status` | Role A "APIs and integrations" |
| Concurrency (goroutines, channels, worker pool, context) | `device-gateway` | Role A "distributed systems"; both roles' general Go depth |
| Raw protocol work (hand-rolled RESP client) | `device-gateway` | Role A "communication protocols" |
| Container split by responsibility, independently deployable services | Whole StreamFlix-7 architecture | Role A "scalable and maintainable services for a growing ecosystem" |
| Deliberate fault injection and reading the resulting alarm trail | `chaos-agent` + `core-engine` | Role A "troubleshooting complex technical issues"; Role A "reliability" |
| Northbound interface, decoupled from internals | `northbound` | Role B "access across devices, networks, and admin platforms" (the *pattern* — an external-facing, internals-hidden interface) |

## What's still missing — and what this addition (`auth-service`) closes

| Gap | Role(s) | Closed by |
|---|---|---|
| No tests anywhere in the repo | A ("writing tests and ensuring high code quality") | `auth-service` ships with real `_test.go` files for both the token logic and the HTTP middleware, and the Dockerfile runs `go test ./...` as a build step — a failing test fails the build, the same gate a real CI pipeline enforces. |
| No identity/authorization/RBAC system | B (the whole role, essentially) | `auth-service`: login, token issuance, role-based permission checks, an admin-only endpoint. |
| No explicit "what data goes where" design artifact | B ("help define what data should be persisted in AWS-based cloud services") | `ADR-0001-identity-data-store.md` — a real architecture-decision record weighing RDS vs. DynamoDB for exactly this service, written the way you'd actually write one at work. |
| No code-review/architecture-discussion practice material | A + B ("architectural discussions," "architecture decisions," "product-first mindset") | The ADR *is* that practice — it's built to be read out loud and argued with, not just filed away. |
| No account of "access across devices, networks, admin platforms" | B | `auth-service`'s RBAC model uses roles (`admin`, `operator`, `viewer`) with device-scoped permissions (`devices:read`, `devices:write`, `devices:admin`) — a small but real version of exactly this. |
| No cross-team-interface story | B ("collaborate with cloud, mobile, web, embedded-adjacent teams") | The token format (`header.payload.signature`, same shape as a JWT) is intentionally something a mobile app, a web app, or an embedded device could all consume identically — one contract, many client types. This is the concrete thing to point to when asked "how would other teams use what you build." |

## What's *not* addressed here, on purpose

- **Real AWS.** Nothing in this repo touches an actual AWS account — that's out of
  scope for a local learning stack. What's covered instead is the *design thinking*
  (the ADR) and a `UserStore` interface written so that swapping the in-memory
  implementation for a real DynamoDB- or RDS-backed one wouldn't touch a single HTTP
  handler. Know that seam, and you can talk credibly about the AWS side without
  having deployed anything.
- **Product-leadership collaboration and fast risk-based prioritization** (Role B's
  last two bullets). These are working-style and seniority signals, not something a
  repo demonstrates. The honest prep for these is interview-narrative: have one real
  story ready about a time you had to make an engineering call with incomplete
  information and explain your reasoning, not just the outcome.

See `GO-STEP-BY-STEP-GUIDE.md` for the build, and `ADR-0001-identity-data-store.md`
for the AWS data-persistence design document.
