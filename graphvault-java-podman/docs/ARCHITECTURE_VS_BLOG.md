# GraphVault vs. the Real Thing

This document exists so nobody walks away from this repo believing it IS Netflix's
architecture. It's a small, honest model of it. This page goes claim by claim: what the
talk/blog said, what this repo does about it, and exactly where and why we simplified.

Source material: Paul Bakker's "How Netflix Uses Java" talk series (QCon SF 2023, JavaOne 2025,
JavaOne 2026) and ByteByteGo's "How Netflix Runs on Java?" deep-dive on the same architecture.

---

## 1. "A federated GraphQL platform connecting client apps to dozens of Java backend services"

**Real Netflix:** Apollo Federation. Each DGS publishes a subgraph schema with federation
directives (`@key`, `@extends`, `@external`, `@requires`). A federation-aware gateway (or
Apollo Router) composes every subgraph's schema into one "supergraph" at build/deploy time,
computes a query plan for each incoming query, and executes that plan — including stitching
fields from DIFFERENT subgraphs onto the SAME type (e.g., `Title.artwork` physically resolved
by a different service than `Title.name`, but appearing as one flat `Title` object to clients).

**This repo:** `graphql-gateway` parses the incoming query with graphql-java's real parser,
groups TOP-LEVEL fields by which subgraph owns them (a hardcoded map in
`application.yml` / `GatewayProperties`), fans those groups out in parallel, and unions the
results. It cannot merge two subgraphs' fields onto one object type — `artworkForTitle` and
`title` are siblings in this repo's schema, not `Title.artwork`.

**Why we drew the line here:** Implementing real federation directives means each DGS also has
to implement `_entities` resolvers (the reference-resolution mechanism federation uses to let
one subgraph "look up" an entity by the key another subgraph gave it) and the gateway needs a
proper query planner, not a field-ownership map. That's a legitimate multi-week project on its
own; doing it partially would risk teaching subtly wrong mental models. Doing federation-lite,
loudly labeled as such, teaches the *shape* of the problem (split a query, fan out, merge)
honestly instead of half-teaching the real protocol.

**If you want to go further:** swap `graphql-gateway` for Apollo Router (a real, open-source,
federation-compliant gateway distributed as a single binary/container image) and add
`com.netflix.graphql.dgs:dgs-federation` to each DGS's dependencies to get real `@key` support.
You'd then compose a real supergraph schema with Apollo's `rover` CLI. This is a meaningful
stretch goal, not a small tweak.

---

## 2. "Every Domain Graph Service (DGS) at Netflix is a Spring Boot application"

**Real Netflix / this repo:** This one we did for real. `titles-dgs`, `artwork-dgs`, and
`availability-dgs` all use `com.netflix.graphql.dgs:graphql-dgs-spring-graphql-starter` — the
actual, currently-maintained Netflix open-source DGS Framework (the older
`graphql-dgs-spring-boot-starter` has had no releases since November 2024; the framework's
own team moved its transport layer onto Spring's official `spring-graphql` project while
keeping the same `@DgsComponent`/`@DgsQuery`/`@DgsData` programming model). Same annotations,
same "resolvers are just annotated Spring components" idea the blog describes.

**What's NOT real:** the DGS-Netflix stack the blog mentions also includes Netflix-internal
Spring Security annotations (`@Secured`, `@PreAuthorize` wired to Netflix's own auth), a
service-mesh-integrated retry/timeout layer, and Micrometer wired to Netflix-scale internal
telemetry pipelines. None of that is public, so none of it is here. Standard Spring Boot
Actuator health/info endpoints stand in for it.

---

## 3. "Inside the backend, services communicate over gRPC... strong typing via Protocol Buffers"

**This repo:** for real, not simulated. `asset.proto` is a genuine Protocol Buffers contract,
compiled by `protobuf-maven-plugin` into real generated Java classes at build time.
`artwork-dgs` calls `asset-service` through a real `AssetServiceGrpc.AssetServiceBlockingStub`
over a real (plaintext, unauthenticated) gRPC channel.

**What's simplified:** the `.proto` file is duplicated by hand into both modules'
`src/main/proto/`. Real organizations solve this with a shared schema repository or an
internal package registry so client and server can never silently drift — see the comment at
the top of `asset.proto` itself. There's also no TLS (see item 6 below) and no gRPC
interceptors for auth, retries, or deadlines, all of which a real internal gRPC stack would
have.

---

## 4. "Now default to ZGC... spend more CPU to lower pause times... mostly Java 21 and 25"

**This repo:** every service's Containerfile launches its jar with `-XX:+UseZGC
-XX:+ZGenerational` on a JDK 21 base image (`eclipse-temurin:21-jre-jammy`). We pinned to 21
specifically (rather than a newer JDK) because on JDK 21, generational mode is opt-in and needs
BOTH flags together — on JDK 23+, generational became ZGC's only mode and `-XX:+ZGenerational`
is unrecognized. Using JDK 21 lets us show the flags exactly as the talk-era Netflix fleet
would have needed them.

**What's not reproduced:** we don't have Netflix-scale traffic to actually observe a pause-time
or CPU trade-off — there's nothing here to benchmark against. If you want to see the effect
yourself, the Java Virtual Threads section of `docs/JAVA_SPRING_BOOT_EXPLAINED.md` links to how
you'd load-test this stack and compare GC logs between G1 and ZGC.

---

## 5. Virtual threads ("developers don't need to use new APIs... resolvers could now run in
parallel by default")

**This repo:** `spring.threads.virtual.enabled: true` is set in every service's
`application.yml` — the one-line, no-code-change switch the blog describes, which moves every
Tomcat request-handling thread onto a JVM-scheduled virtual thread. On top of that,
`graphql-gateway` ALSO shows the more explicit, manual version: an
`Executors.newVirtualThreadPerTaskExecutor()` bean used to fan a single request out into
parallel calls to each subgraph, then join them — this is what the blog's "developers had to
reason about thread pools, manage CompletableFutures" sentence is describing being made cheap
enough to bother with.

**What's not reproduced:** the thread-pinning-on-`synchronized` failure mode that made Netflix
temporarily roll virtual threads back (fixed in JDK 24) isn't something you'll hit in this
small repo — none of our code holds a `synchronized` block across a blocking call. It's real
and worth knowing about if you extend this project with your own libraries, some of which may
still use `synchronized` internally.

---

## 6. What we didn't even attempt

- **A real service mesh.** Real Netflix's internal traffic (per the blog) runs through a
  proxy-based mesh, internally called ProxyD, handling TLS, discovery, and retries
  transparently. That's proprietary and not something we can reproduce; Podman's built-in
  container-name DNS resolution stands in for "discovery" only, with nothing standing in for
  mTLS or retry policy.
- **Eureka.** If you've seen an earlier "Netflix clone" project in this series that used Eureka
  + Spring Cloud Gateway + a Zuul-successor routing layer — that's real Netflix history (Eureka,
  Ribbon, Hystrix, and Zuul are genuinely Netflix-originated open source projects), but it's
  the OLD pattern. The 2023 QCon talk this whole series traces back to opens by explicitly
  correcting the assumption that Netflix today is "all RxJava microservices with Hystrix and
  Spring Cloud... Chaos Monkeys running the show." GraphVault is the newer picture; that
  earlier project is deliberately left as-is elsewhere in this series so you can compare both
  eras side by side.
- **AI-driven migration tooling.** The talk's closing section covers using Claude Code to drive
  Netflix's own Spring Boot 4 migration and building Spring AI-based agentic workflows
  internally. There's no code-migration-agent equivalent in this repo — that's a workflow, not
  an architecture, and out of scope for a from-scratch reference project.
