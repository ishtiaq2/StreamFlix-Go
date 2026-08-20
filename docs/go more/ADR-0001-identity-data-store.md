# ADR-0001: Where should identity and permissions data live?

**Status:** Proposed
**Context for this ADR:** `auth-service`'s `UserStore` interface (see
`cmd/authsvc/main.go`) is currently satisfied by an in-memory map. This ADR is the
design document for what replaces it in an AWS-based deployment — exactly the kind of
artifact Role B's "help define what data should be persisted in AWS-based cloud
services" points at. Written the way you'd actually write one at work: a real
recommendation, not a menu of options with no opinion.

---

## Context

`auth-service` needs to persist:

- **Users** — id, email, password hash, role. Read on every login; written rarely
  (signup, role change).
- **Roles → permissions** — currently a static map in code (`rolePermissions` in
  `internal/auth/rbac.go`). Small, changes rarely, read on every authorization check.
- (Not yet built, but foreseeable) **Sessions/refresh tokens** or a **revocation
  list**, if tokens need to be invalidated before they naturally expire.

Read pattern dominates: logins and permission checks vastly outnumber user creation or
role changes. That access pattern is the main thing that should drive this decision —
not which database is more familiar or more fashionable.

## Decision

**Use Amazon RDS (Postgres) for users and roles, not DynamoDB, for this service at its
current scale.** Reassess if/when the access pattern or scale changes materially (see
"Revisit if" below).

## Why RDS over DynamoDB here

- **The data is genuinely relational.** Users have roles; roles have permissions;
  a future "teams" or "organizations" concept would add another relationship on top.
  Modeling that in DynamoDB means either denormalizing (duplicating permission data
  onto every user row, and now updating N rows every time a role's permissions change)
  or maintaining multiple tables and doing the joins in application code — Postgres
  does this natively and correctly, for free.
- **The scale doesn't justify DynamoDB's trade-offs.** DynamoDB earns its complexity
  (single-digit-millisecond reads at effectively unlimited scale, but rigid,
  access-pattern-first schema design) when you're serving millions of requests per
  second across a huge, unpredictable key space. An identity service for one product's
  admin/operator/viewer user base is not that — it's a few tables, moderate read
  volume, and a strong need for consistency (a role change should be visible on the
  *next* request, not eventually).
- **Strong consistency matters for authorization specifically.** DynamoDB's default
  reads are eventually consistent (strongly consistent reads are available but cost
  more and don't apply to Global Secondary Indexes). For "did we just revoke this
  user's admin role," eventual consistency is the wrong default to have to think
  around. RDS gives you read-your-writes consistency without extra configuration.
- **Query flexibility during development.** Being able to run an ad-hoc `SELECT`
  against production data during an incident, or add an index without redesigning
  access patterns, is worth a lot at this stage of the system's life.

## Why not DynamoDB (stated plainly, not just "RDS wins by default")

DynamoDB is the better choice when: the access pattern is simple and known up front
(single-key lookups), scale is large or spiky, and operational overhead needs to be
near zero. None of those describe this service yet. If they come to describe it later,
that's a reason to revisit, not a reason to over-build for it now.

## Consequences

- **Adds an RDS instance to operate** — backups, patching, connection pooling (a
  Go service under load needs a bounded connection pool, e.g. via `database/sql`'s
  `SetMaxOpenConns`, or it can exhaust Postgres's connection limit under a traffic
  spike — worth flagging as a follow-up hardening task).
- **Schema migrations become a real practice**, not just an `init.sql` run once. A
  tool like `golang-migrate` or `goose` should own this before the service has real
  users.
- **`UserStore` stays the seam.** A `PostgresStore` implementing the existing
  `UserStore` interface (`GetByEmail`, `Create`, `List`) is the entire integration
  surface — no handler in `cmd/authsvc/main.go` changes.

## Revisit if

- Read volume grows by an order of magnitude and starts being dominated by simple
  key-value lookups (e.g., token validation against a huge revocation list) rather
  than relational queries — that specific sub-problem (not the whole service) might
  move to DynamoDB or a cache like ElastiCache/Redis, sitting in front of or beside
  RDS rather than replacing it wholesale.
- The system grows a genuinely different access pattern — e.g., per-device permission
  checks at a scale and latency requirement RDS can't comfortably serve.

## Alternatives considered

| Option | Rejected because |
|---|---|
| DynamoDB for everything | Relational shape, low-to-moderate scale, consistency needs — see above. |
| Aurora Serverless v2 instead of standard RDS | Reasonable alternative, not a different *kind* of decision — worth a follow-up cost/ops comparison once real traffic numbers exist, not a blocker now. |
| Keep it in-memory permanently | No durability across restarts or across multiple service instances — breaks the moment this runs as more than one replica. |
