package com.graphvault.gateway.controller;

import com.graphvault.gateway.client.SubgraphClient;
import com.graphvault.gateway.client.SubgraphResult;
import com.graphvault.gateway.merge.ResponseMerger;
import com.graphvault.gateway.planner.QueryPlanner;
import com.graphvault.gateway.planner.SubgraphPlan;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Every client request in this entire platform lands here first. The method body below is a
 * direct, readable expression of the blog's fan-out description: "The API Gateway receives the
 * request. It contacts 2-3 DGSs to resolve fields... This fan-out pattern is essential for
 * flexibility but introduces real complexity."
 *
 * Notice what ISN'T here: no thread pool sizing, no {@code Mono.zip(...)}, no callback
 * chaining. Each downstream call is submitted to the virtual-thread executor
 * ({@link com.graphvault.gateway.config.ExecutorConfig}) as a {@link CompletableFuture}, and
 * {@code .join()} on each one blocks only the (cheap, virtual) thread running that one future —
 * never a scarce platform thread. If titles-dgs answers in 5ms and availability-dgs takes 40ms,
 * total latency here is ~40ms, not 45ms, with none of the reactive-operator complexity that
 * same parallelism would have needed a few years ago.
 */
@RestController
public class GatewayController {

    private final QueryPlanner queryPlanner;
    private final SubgraphClient subgraphClient;
    private final ResponseMerger responseMerger;
    private final ExecutorService fanOutExecutor;

    public GatewayController(QueryPlanner queryPlanner, SubgraphClient subgraphClient,
                              ResponseMerger responseMerger, ExecutorService fanOutExecutor) {
        this.queryPlanner = queryPlanner;
        this.subgraphClient = subgraphClient;
        this.responseMerger = responseMerger;
        this.fanOutExecutor = fanOutExecutor;
    }

    @PostMapping("/graphql")
    public ResponseEntity<Map<String, Object>> handle(@RequestBody GraphQLRequest request) {
        List<SubgraphPlan> plans;
        try {
            plans = queryPlanner.plan(request.query());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("errors", List.of(Map.of("message", e.getMessage()))));
        }

        // Fan out: one virtual thread per subgraph this query touches, all started before any
        // of them are awaited.
        List<CompletableFuture<SubgraphResult>> futures = plans.stream()
                .map(plan -> CompletableFuture.supplyAsync(() -> subgraphClient.execute(plan), fanOutExecutor))
                .toList();

        // Fan in: .join() each future in turn. Because every future was already started above,
        // this loop's total wall-clock time is bounded by the SLOWEST subgraph call, not the
        // sum of all of them.
        List<SubgraphResult> results = futures.stream().map(CompletableFuture::join).toList();

        return ResponseEntity.ok(responseMerger.merge(results));
    }
}
