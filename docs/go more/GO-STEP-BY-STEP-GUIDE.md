# Step-by-Step: Adding a Go Identity & Access Service to StreamFlix-7

Continues from the main [STEP-BY-STEP-GUIDE.md](./STEP-BY-STEP-GUIDE.md) (Parts 1–15).
This adds an 8th (or 9th, if `device-gateway` from Phase 2 is already in place)
container: `auth-service`, a Go identity/authorization/RBAC backend, built to close
the gaps identified in `CAREER-GAP-ANALYSIS-GO.md`.

---

## Part 16 — The idea, in one paragraph

Everything built so far talks to everything else with no notion of "who is asking."
`auth-service` adds that layer: a user logs in with email/password, gets back a
signed token, and that token proves both **identity** (who they are) and
**authorization** (what role they hold, and therefore what they're allowed to do).
Every other service in a real system — `catalog-api`, `northbound`, `device-gateway`
— would eventually check this token before acting on a request. This part builds the
identity service itself; wiring the others to require it is listed as a "what's next"
exercise at the end, the same way Part 15 of the main guide does.

---

## Part 17 — Project layout

```bash
mkdir -p auth-service/{cmd/authsvc,internal/auth}
cd auth-service
```

Three files carry the actual logic, deliberately separated by responsibility:

- `internal/auth/token.go` — pure token issuance/verification, no HTTP, no storage.
- `internal/auth/rbac.go` — pure role→permission logic, no HTTP, no storage.
- `internal/auth/middleware.go` — the HTTP glue connecting tokens and RBAC to
  `net/http` requests.
- `cmd/authsvc/main.go` — the server: storage, handlers, routing.

This split is worth explaining out loud in an interview: **the parts that are easiest
to get subtly wrong (crypto, authorization logic) are also the parts kept free of
HTTP and storage concerns**, which is exactly what makes them straightforward to unit
test in isolation (Part 20).

---

## Part 18 — `internal/auth/token.go`: what a token actually is

### Step 1 — The shape

A token here is three base64url-encoded pieces joined by dots:
`header.payload.signature` — the same shape as a real JWT (JSON Web Token). Reading
`NewToken` top to bottom:

1. **Header** — a fixed JSON blob describing the algorithm (`HS256`). Encoded once,
   never changes.
2. **Payload** — the `Claims` struct (subject, role, expiry) marshaled to JSON, then
   base64url-encoded. This is *not* encrypted — anyone can decode a JWT-shaped token
   and read the claims. The security property a token like this provides is
   **integrity** (nobody can change it undetected), not **confidentiality** (nobody
   can read it). That distinction is a very common thing to be asked to explain.
3. **Signature** — an HMAC-SHA256 of `header + "." + payload`, computed with a secret
   only the server knows. Anyone can decode the payload; only someone with the secret
   can produce a signature that verifies.

### Step 2 — Verifying, and why `hmac.Equal` instead of `==`

`ParseToken` recomputes the expected signature and compares it to the one on the
token using `hmac.Equal`, not Go's `==` operator. `==` on byte slices compares them
left to right and returns as soon as it finds a mismatch — which means a wrong
signature that matches the correct one for longer takes measurably longer to reject.
An attacker who can measure response time closely enough can use that timing
difference to guess the correct signature one byte at a time (a **timing attack**).
`hmac.Equal` always takes the same amount of time regardless of where the mismatch
is. Small detail, real vulnerability class — worth having ready as an example of
"security thinking" in an interview.

### Step 3 — Expiry

`ExpiresAt` is checked as the very last step, after the signature is already known
to be valid. An expired-but-validly-signed token is still rejected — expiry is a
separate check from authenticity, and both have to pass.

---

## Part 19 — `internal/auth/rbac.go` and `middleware.go`: authorization

### Step 1 — Permissions, not just roles

`rolePermissions` maps role names (`admin`, `operator`, `viewer`) to sets of granular
`Permission` values (`users:read`, `devices:write`, and so on) rather than having
handlers check `if claims.Role == "admin"` directly. The payoff: if a new role needs
"can read devices but not write them," it's a one-line addition to the map, not a
hunt through every handler for role checks to update. This is the pattern behind
real-world RBAC systems, including cloud IAM policies — a role is just a named
bundle of permissions.

### Step 2 — Two middlewares, one shared helper

`RequireAuth` (any valid token) and `RequirePermission` (valid token *and* a specific
permission) both call the same private `claimsFromRequest` helper — extract the
`Authorization: Bearer <token>` header, parse it, return claims or an error. Neither
middleware duplicates that logic. Small thing, but "don't repeat the security-
sensitive code path" is exactly the kind of habit a code review comment would call
out if it *weren't* done this way.

### Step 3 — Passing identity down via context

Once a request passes a middleware, its claims are attached with
`context.WithValue` and retrieved downstream with `ClaimsFromContext`. This is the
idiomatic Go way to pass per-request data through a handler chain without changing
every function signature to accept a `*Claims` parameter explicitly.

---

## Part 20 — Tests: `token_test.go` and `rbac_test.go`

This is the part of the whole StreamFlix-7 project that most directly answers
"writing tests and ensuring high code quality." Walk through what each test is
actually defending against, not just that it exists:

| Test | What it actually catches |
|---|---|
| `TestNewAndParseToken` | The basic round trip works at all — the baseline "did I break token issuance" check. |
| `TestParseToken_TamperedSignatureRejected` | A modified token is rejected — the core security property of the whole system. |
| `TestParseToken_ExpiredRejected` | Expiry is actually enforced, not just recorded. |
| `TestParseToken_WrongSecretRejected` | A token signed with a different secret (e.g., from a different environment, or forged) is rejected. |
| `TestParseToken_MalformedRejected` | Garbage input doesn't panic or silently succeed — a table-driven test over several malformed shapes at once. |
| `TestHasPermission` | The RBAC table behaves correctly for allowed, disallowed, and unknown roles — also table-driven. |
| `TestRequirePermission_Middleware` | The HTTP layer actually enforces all of the above end to end: no token → 401, wrong role → 403, right role → 200 and the wrapped handler runs. Uses `httptest.NewRequest`/`httptest.NewRecorder`, so no real network socket or running server is needed to test HTTP behavior — a pattern worth knowing cold for a Go interview. |

Run them:

```bash
cd auth-service
go test ./... -v
```

---

## Part 21 — `cmd/authsvc/main.go`: wiring it into a server

### Step 1 — The storage seam

`UserStore` is an interface with three methods (`GetByEmail`, `Create`, `List`).
`InMemoryStore` implements it today, guarded by a `sync.RWMutex` for the same reason
`device-gateway`'s `Registry` was in Phase 2 — multiple requests can read and write
concurrently. Every handler below depends only on the `UserStore` interface, never on
`InMemoryStore` directly — that's what makes `ADR-0001-identity-data-store.md`'s
eventual `PostgresStore` a drop-in replacement rather than a rewrite.

### Step 2 — The three real endpoints

- `POST /login` — checks credentials, issues a token. Deliberately returns the same
  error for "no such user" and "wrong password" so a caller can't use the error
  message to enumerate valid emails.
- `GET /me` — behind `RequireAuth` only (any authenticated identity). Returns the
  caller's own claims — the simplest possible "prove this works" endpoint.
- `GET|POST /admin/users` — behind `RequirePermission(..., PermUsersRead/Write)`.
  `GET` lists users, `POST` creates one. This is the concrete stand-in for "improve
  systems that manage access across devices, networks, and admin platforms" — an
  admin-only surface, gated by role, is exactly that pattern in miniature.

### Step 3 — `methodSwitch`

One path (`/admin/users`), two methods, two different permission requirements
(`users:read` for GET, `users:write` for POST). `methodSwitch` is a small explicit
router for that, rather than reaching for a third-party router library — worth being
able to write from scratch, since "how would you route by method without a
framework" is a fair Go interview question.

---

## Part 22 — `Dockerfile`: tests as a build gate

```dockerfile
RUN go test ./...
RUN go build -o /out/auth-service ./cmd/authsvc
```

This ordering is the whole point: if any test fails, `go test ./...` exits non-zero,
the `RUN` step fails, and the image build stops — `go build` never even runs. No
image can be produced from code whose own tests don't pass. This is a miniature,
honest version of what a real CI/CD pipeline does at a larger scale (build → test →
only then produce/publish an artifact) — a good bridge into talking about the CI/CD
gap noted in `CAREER-GAP-ANALYSIS.md` from Phase 2.

---

## Part 23 — Wire it into `podman-compose.yml`

```yaml
  auth-service:
    build: ./auth-service
    container_name: auth-service
    environment:
      AUTH_SECRET: "dev-secret-change-me"
      HTTP_ADDR: ":8083"
    networks: [streamflix-net]
    ports:
      - "8083:8083"
```

Copy the `auth-service/` folder into `streamflix-stack/auth-service/`, next to
`core-engine/` and the others.

---

## Part 24 — Run it and walk the flow end to end

```bash
cd streamflix-stack
podman-compose up -d --build auth-service
podman-compose logs -f auth-service
```

**Log in as the seeded admin:**

```bash
curl -s -X POST http://localhost:8083/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@streamflix.local","password":"changeme"}'
```

Copy the `token` value from the response into a shell variable:

```bash
TOKEN="paste-the-token-here"
```

**Check identity:**

```bash
curl -s http://localhost:8083/me -H "Authorization: Bearer $TOKEN"
```

**Create a new, lower-privileged user (admin-only action):**

```bash
curl -s -X POST http://localhost:8083/admin/users \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"email":"ops@streamflix.local","password":"opspass","role":"operator"}'
```

**Prove RBAC actually blocks the wrong role** — log in as the new operator and try
the same admin endpoint:

```bash
OPS_TOKEN=$(curl -s -X POST http://localhost:8083/login \
  -H "Content-Type: application/json" \
  -d '{"email":"ops@streamflix.local","password":"opspass"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['token'])")

curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8083/admin/users \
  -H "Authorization: Bearer $OPS_TOKEN"
```

Expect `403` — the operator's token is valid (proves the token system works) but
lacks `users:read` (proves RBAC works). Seeing both a `200` (as admin) and a `403`
(as operator) against the *same endpoint* is the clearest possible demonstration
that this is doing real authorization, not just checking "is there a token."

---

## Part 25 — Troubleshooting

- **`401` on every request, even with a token** — check the `Authorization` header
  is exactly `Bearer <token>` with one space; `claimsFromRequest` requires that
  literal prefix.
- **Tokens stop validating after restarting the container** — `AUTH_SECRET`
  defaults to a fixed dev value in code, but if you've overridden it via environment
  variable and the container restarted with a different value, old tokens signed
  with the previous secret will correctly fail — that's `TestParseToken_WrongSecretRejected`'s
  scenario happening for real. Expected behavior, not a bug.
- **`go test ./...` fails during the Docker build** — good, that's the gate working.
  Run `go test ./... -v` locally first to see exactly which case failed before
  rebuilding the image.
- **`/admin/users` returns `405`** — only `GET` and `POST` are wired in
  `methodSwitch`; any other method hits the `Allow` header fallback.

---

## Part 26 — Mapping the code back to both role descriptions

Worth rehearsing this mapping directly before an interview — being able to point at
a specific file for a specific bullet point is far more convincing than describing
the project in the abstract.

| Posting bullet | Where it lives |
|---|---|
| "Design and develop backend services in Go" | The whole `auth-service` |
| "Build systems for identity, authorization, roles, and permissions" | `internal/auth/token.go` (identity), `internal/auth/rbac.go` (roles/permissions), `internal/auth/middleware.go` (authorization enforcement) |
| "Help define what data should be persisted in AWS-based cloud services" | `ADR-0001-identity-data-store.md` |
| "Improve systems that manage access across devices, networks, and admin platforms" | `/admin/users`, gated by `PermUsersRead`/`PermUsersWrite` |
| "Writing tests and ensuring high code quality" | `token_test.go`, `rbac_test.go`, and the test-gated Dockerfile |
| "Collaborate with cloud, mobile, web, embedded-adjacent teams" | The token's JWT-shaped, framework-agnostic format — any client type can consume it identically |
| "Contribute to architecture decisions with a pragmatic, product-first mindset" | The `UserStore` interface seam, and ADR-0001's explicit "why not DynamoDB" section — a real decision with real trade-offs, not just a options list |

---

## Part 27 — What's next

- Wire `catalog-api` and `northbound` to actually require a valid token from
  `auth-service`, so the whole stack — not just `auth-service` itself — demonstrates
  end-to-end authorization.
- Replace `InMemoryStore` with a real `PostgresStore` against the existing
  `database` container, following ADR-0001's recommendation, and add a second ADR
  once real query patterns are known.
- Add a `/logout`/revocation path — the current design has no way to invalidate a
  token before it expires, a real and known limitation of stateless tokens worth
  being able to discuss.
- Add a GitHub Actions workflow that runs `go test ./...` on every push — the same
  gate the Dockerfile already enforces locally, now enforced before merge.
