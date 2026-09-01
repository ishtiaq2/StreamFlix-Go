# Step-by-Step Guide

## 0. Prerequisites

```bash
# Podman + podman-compose (Fedora/RHEL example; use your distro's package manager)
sudo dnf install -y podman podman-compose

# Java 21 + Maven, only needed if you want to build/run services outside containers too
sudo dnf install -y java-21-openjdk-devel maven

java -version   # should print 21.x
podman -v
podman-compose -v
```

You'll need real internet access to Maven Central the first time you build — Spring Boot, the
DGS Framework, and gRPC/Protobuf dependencies all come from there, and
`protobuf-maven-plugin` downloads a matching `protoc` binary on first use.

## 1. Clone and look around

```bash
git clone <your-fork-url> graphvault && cd graphvault
```

Read `README.md` for the architecture diagram, then skim
`docs/ARCHITECTURE_VS_BLOG.md` before writing any code of your own against this — it tells you
exactly which parts are "real Netflix pattern" and which parts are simplified for teaching.

## 2. Build everything

```bash
chmod +x scripts/build-all.sh
./scripts/build-all.sh
```

This runs `mvn clean package` in each of the 5 Java modules, in order. If `asset-service` or
`artwork-dgs` fail at the `protobuf-maven-plugin` step, see Troubleshooting below — it's almost
always a network/proxy issue reaching Maven Central for the protoc binary.

## 3. Bring the whole stack up

```bash
podman-compose -f podman-compose.yml up --build
```

Watch the logs. You should see, in roughly this order:
1. `asset-service` starts its gRPC server on port 9090
2. `titles-dgs` and `availability-dgs` start, each running their own `data.sql` seed
3. `artwork-dgs` starts and connects its gRPC client channel to `asset-service`
4. `graphql-gateway-1` and `graphql-gateway-2` start
5. `nginx-lb` starts last (its `depends_on` waits for both gateway replicas)

## 4. Try it — direct subgraph queries first

Each DGS has its own GraphiQL, useful for confirming a service works in isolation before
trusting the gateway's fan-out:

- http://localhost:8081/graphiql → try `{ titles { id name releaseYear } }`
- http://localhost:8083/graphiql → try `{ availabilityForTitle(titleId: "1") { region available } }`
- http://localhost:8082/graphiql → try `{ artworkForTitle(titleId: "1") { type url } }` — if
  this one fails but the others work, the problem is almost always the gRPC channel to
  `asset-service` (see Troubleshooting).

## 5. Try it — the federated gateway

Open **http://localhost/graphiql.html** (note: `.html`, served by the gateway itself, going
through `nginx-lb` on port 80). Run the default query — it fans out to all three DGSs and
returns one merged response:

```graphql
query {
  title(id: "1") {
    id
    name
    releaseYear
    synopsis
  }
  artworkForTitle(titleId: "1") {
    type
    url
  }
  availabilityForTitle(titleId: "1") {
    region
    available
  }
}
```

Remember: this gateway doesn't support GraphQL `variables` — always inline arguments as
literals (see `docs/ARCHITECTURE_VS_BLOG.md`, item 1, for why).

## 6. Watch the load balancer actually balance

```bash
# Hit the LB a bunch of times and watch which gateway container's logs light up
for i in $(seq 1 10); do
  curl -s -X POST http://localhost/graphql \
    -H "Content-Type: application/json" \
    -d '{"query":"query { titles { id name } }"}' > /dev/null
done

podman logs graphql-gateway-1 --tail 5
podman logs graphql-gateway-2 --tail 5
```

You should see requests split across both containers (nginx's `least_conn` balancing).

## 7. Verify the gRPC hop directly (optional but recommended)

`grpcurl` (a `curl`-like tool for gRPC) is the easiest way to bypass artwork-dgs entirely and
confirm `asset-service` is answering correctly on its own:

```bash
# Install grpcurl (see https://github.com/fullstorydev/grpcurl for your platform)
grpcurl -plaintext -d '{"title_id": 1}' \
  -import-path ./asset-service/src/main/proto -proto asset.proto \
  localhost:9090 graphvault.asset.v1.AssetService/GetArtworkUrls
```

If this works but artwork-dgs's `artworkForTitle` query doesn't, the bug is in artwork-dgs's
gRPC client wiring, not in asset-service.

## 8. Scale a DGS horizontally (see Eureka-free load balancing in action)

```bash
podman-compose -f podman-compose.yml up --scale titles-dgs=3 -d
```

Wait — this WON'T actually load-balance across the 3 replicas the way it would with Eureka in
the picture, because `graphql-gateway`'s config points at a single hardcoded hostname
(`http://titles-dgs:8081/graphql`) and Podman's compose networking will only round-robin
plain container-to-container DNS lookups if you also remove the fixed `container_name` from
that service in `podman-compose.yml` (fixed names prevent Podman from creating the multiple
replicas' load-balanced DNS entry). This is intentional friction — it's the clearest way to
FEEL why real Netflix needs either a client-side registry (the Eureka pattern from the earlier
project in this series) or a proper service mesh (their real ProxyD-based one) once you have
more than one replica of an internal service. See `docs/ARCHITECTURE_VS_BLOG.md` item 6.

## Troubleshooting

**`protobuf-maven-plugin` fails to download protoc / hangs.** You're likely behind a
proxy/firewall that blocks `repo.maven.apache.org` or GitHub release assets. Options: (a) run
the build somewhere with open internet access once, then reuse your local `~/.m2/repository`
cache; (b) install `protobuf-compiler` via your package manager and configure
`protobuf-maven-plugin`'s `<protocExecutable>` to point at the system `protoc` binary instead
of `<protocArtifact>`.

**`artwork-dgs` starts but every `artworkForTitle` query times out or errors.** Almost always
means `asset-service` isn't reachable yet. Check `podman ps` to confirm `asset-service` is
actually running, then check `artwork-dgs`'s logs for a gRPC `UNAVAILABLE` status. `depends_on`
in `podman-compose.yml` controls START ORDER, not "wait until healthy" — if `asset-service`
takes a few extra seconds to bind its gRPC port, `artwork-dgs` may try its first call before
it's ready. Retrying the GraphQL query a few seconds later should succeed once both are up.

**Gateway returns `"No subgraph is configured to own field..."`.** You queried a field name
that isn't in `graphql-gateway/src/main/resources/application.yml`'s `graphvault.field-owners`
map. Either you made a typo, or you added a new query field to a DGS's schema and forgot to
register it in the gateway's routing table (see `docs/ARCHITECTURE_VS_BLOG.md` item 1 for why
this is manual here).

**Everything builds but `podman-compose up` says a port is already in use.** Something else on
your machine is already listening on 80, 8080-8083, 8180, or 9090. Either stop that process or
edit the `ports:` mappings in `podman-compose.yml` (the container-internal ports on the right
side of each `:` must stay the same, since application.yml files hardcode those).
