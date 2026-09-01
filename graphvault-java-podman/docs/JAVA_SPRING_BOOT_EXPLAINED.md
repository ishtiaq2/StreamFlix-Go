# The Java and Spring Boot, Explained

This walks through every non-obvious Java/Spring Boot idea in this repo, service by service, in
the order you'd want to read the code.

## 1. `asset-service` — plain gRPC, no web framework at all

Open `AssetServiceApplication.java`. It's a `@SpringBootApplication` with no
`spring-boot-starter-web` on its classpath at all — there's no embedded Tomcat, no REST
controllers. Its only job is running a gRPC server, which `net.devh:grpc-server-spring-boot-starter`
auto-configures based on `application.yml`'s `grpc.server.port` (see that file).

`AssetGrpcService.java` extends `AssetServiceGrpc.AssetServiceImplBase` — a class you will
never see in the source tree, because it's generated at build time from `asset.proto` by the
`protobuf-maven-plugin` block in `pom.xml`. If you want to actually see the generated code:

```bash
cd asset-service
mvn generate-sources
find target/generated-sources -name "*.java"
```

The `@GrpcService` annotation (from the grpc-spring-boot-starter library, not core Spring) is
the gRPC equivalent of `@RestController`: it tells the starter "register this bean as a
handler," the same way Spring MVC discovers `@RestController` beans for HTTP routes.

Inside `getArtworkUrls`, note the `StreamObserver<ArtworkUrlResponse>` parameter. This is
gRPC's callback interface for sending a response: `onNext()` delivers a value, `onCompleted()`
signals the call is done. For this **unary** RPC (one request, one response — declared as plain
`rpc GetArtworkUrls (ArtworkUrlRequest) returns (ArtworkUrlResponse)` in the `.proto`, with no
`stream` keyword), you always call `onNext()` exactly once. The exact same `StreamObserver`
interface is what makes server-streaming RPCs possible elsewhere in gRPC (call `onNext()`
multiple times, then `onCompleted()`) — we don't use that here, but it's why the API looks the
way it does even for a single-response call.

## 2. `titles-dgs` — your first real DGS

Three files matter, read them in this order:

**`schema/schema.graphqls`** — the entire public contract. There is no Java interface anywhere
that says "titles-dgs must expose a `titles` field" — this schema file IS that contract, and
DGS validates your Java code against it at startup, not at request time.

**`TitleDataFetcher.java`** — `@DgsComponent` marks the class for DGS's component scanning.
Each `@DgsQuery`-annotated method's NAME must match a field name under `type Query` in the
schema file exactly (`titles()` answers the `titles` field, `title(Long id)` answers `title(id:
ID!)`). This name-matching is doing the same job `@GetMapping("/titles")` would do in a REST
controller, just via convention instead of an explicit path string.

**`TitleType.java`** — a plain Java `record` DTO, separate from the JPA `Title` entity. Compare
this to `Dtos.java` files in this whole project series: same instinct (never serialize your
persistence layer directly), applied to GraphQL instead of REST JSON.

**Why the separation between DGS response types and JPA entities matters more here than in
REST:** a GraphQL client can ask for ANY subset of a type's fields in any combination. If
`TitleType` were the JPA entity itself and someone added a lazy-loaded relationship to it
later, an innocent-looking query could trigger a `LazyInitializationException` outside of a
transaction, or worse, silently N+1-query your database once per returned row. Mapping
explicitly, even when it's a 1-to-1 field copy today, is what keeps that failure mode from
appearing later without warning.

**`application.yml`'s `spring.threads.virtual.enabled: true`** — this single line switches
every incoming request (HTTP, and therefore GraphQL) onto a JVM-managed virtual thread instead
of a thread from Tomcat's platform-thread pool. You do not need to change a single line of
`TitleDataFetcher.java` to benefit from this — that's the entire point the blog makes about
"developers don't need to use new APIs."

## 3. `artwork-dgs` — the one file that ties GraphQL to gRPC

This is the most important file in the repo: `ArtworkDataFetcher.java`.

```java
@GrpcClient("asset-service")
private AssetServiceGrpc.AssetServiceBlockingStub assetServiceStub;
```

`@GrpcClient` (again from grpc-spring-boot-starter) injects a fully-wired stub — connection
pooling, the underlying `ManagedChannel`, all of it — pointed at whatever
`grpc.client.asset-service.address` resolves to in `application.yml`
(`static://asset-service:9090`, i.e., "resolve the hostname `asset-service` via normal DNS and
connect to port 9090" — Podman's built-in container-name DNS makes that hostname resolve).

Then, inside the `@DgsQuery` method:

```java
ArtworkUrlResponse response = assetServiceStub.getArtworkUrls(request);
```

That's it. One blocking method call that happens to cross a process boundary, over the network,
to a different service, written in the exact same language and shaped as if it were a local
method call. This is the concrete payoff of virtual threads the blog is making a big deal
about: a few years ago, blocking an entire platform thread on a network call inside a
high-throughput service was expensive enough that people reached for reactive types (`Mono`,
`Flux`) specifically to avoid it — which then meant learning a second, harder programming model
for exactly this kind of call. With virtual threads, blocking here is cheap, so you just... call
the method.

## 4. `graphql-gateway` — the deep end

Read these four files together; they form one pipeline for every request:

**`QueryPlanner.java`** parses the incoming query text using `graphql.parser.Parser` — the
literal same parser class the DGS framework and every graphql-java-based server use internally,
not a hand-rolled string splitter. It walks the parsed AST's top-level `Selection`s, groups
them by which subgraph owns each field name (via `GatewayProperties`), and for each group,
BUILDS A NEW AST FRAGMENT (`SelectionSet` → `OperationDefinition` → `Document`) containing only
that subgraph's fields, then prints it back to GraphQL text with `AstPrinter.printAst()`. If
you've never manipulated a parsed AST directly before, this is a good first example: parse,
inspect/transform the tree, print — the exact same three-step shape compilers, formatters, and
linters all share.

**`SubgraphClient.java`** does the actual network call, using Spring's `RestClient` (new in
Spring Framework 6.1) rather than the older `RestTemplate` or the reactive `WebClient`.
`RestClient` is synchronous like `RestTemplate` but with a fluent builder API closer to
`WebClient`'s — a distinctly "2024-and-later Spring" choice, and one that only makes sense
because this call runs on a virtual thread: there's no async-avoidance reason left to prefer
`WebClient` here.

**`ExecutorConfig.java`** exposes one bean: `Executors.newVirtualThreadPerTaskExecutor()`. This
is the STABLE, no-preview-flag-needed API (since Java 21) for "give me a fresh, cheap virtual
thread per task I submit," as opposed to `StructuredTaskScope`, a related but still-preview API
in recent JDKs that we deliberately avoided to keep this buildable without `--enable-preview`.

**`GatewayController.java`** ties it together — and this is the one method worth reading
character by character:

```java
List<CompletableFuture<SubgraphResult>> futures = plans.stream()
        .map(plan -> CompletableFuture.supplyAsync(() -> subgraphClient.execute(plan), fanOutExecutor))
        .toList();

List<SubgraphResult> results = futures.stream().map(CompletableFuture::join).toList();
```

The first `.stream()...toList()` STARTS every subgraph call immediately — by the time that line
finishes, all N HTTP calls are already in flight, each on its own virtual thread. The second
line then waits for each one in turn. Because they were all already running, the total time
this method takes is bounded by the SLOWEST subgraph, not the sum of all of them — the same
fan-out/fan-in shape the blog describes for GraphQL field resolution, written explicitly instead
of happening automatically the way it does inside DGS's own resolver execution.

## 5. What Java 21 buys this repo, all in one place

| Feature | Where it's used | What it replaces |
|---|---|---|
| Virtual threads (`spring.threads.virtual.enabled`) | Every service's HTTP layer | Platform-thread-per-request, tuned thread pools |
| `Executors.newVirtualThreadPerTaskExecutor()` | `graphql-gateway`'s fan-out | A manually-sized fixed thread pool, or reactive operators |
| Records (`record TitleType(...)`) | Every DTO in every service | Lombok `@Data` classes or hand-written getters/equals/hashCode |
| Pattern matching for `instanceof` (`if (selection instanceof Field field)`) | `QueryPlanner.java` | An `instanceof` check followed by a separate manual cast |
| Generational ZGC (`-XX:+UseZGC -XX:+ZGenerational`) | Every Containerfile's `ENTRYPOINT` | G1 (still a perfectly good default; this is what Netflix specifically moved away from, fleet-wide, per the talk) |
