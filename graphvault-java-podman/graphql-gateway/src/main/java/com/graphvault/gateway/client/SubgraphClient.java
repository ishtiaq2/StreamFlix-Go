package com.graphvault.gateway.client;

import com.graphvault.gateway.planner.SubgraphPlan;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Executes exactly one {@link SubgraphPlan} as a plain HTTP POST — GraphQL-over-HTTP is just
 * "POST a JSON body shaped like {@code {"query": "..."}} to an endpoint," which is why any
 * GraphQL server, DGS-based or not, can be called this simply.
 *
 * We use Spring's {@code RestClient} (introduced in Spring Framework 6.1) rather than the
 * older {@code RestTemplate} or the reactive {@code WebClient}. It's synchronous like
 * RestTemplate, with WebClient's nicer fluent API — a deliberately current, idiomatic choice
 * that only makes sense BECAUSE this whole call happens on a virtual thread: blocking here is
 * cheap, so there's no reason to reach for WebClient's non-blocking machinery just to avoid it.
 */
@Component
public class SubgraphClient {

    private final RestClient restClient;

    public SubgraphClient(RestClient.Builder builder) {
        this.restClient = builder.build();
    }

    @SuppressWarnings("unchecked")
    public SubgraphResult execute(SubgraphPlan plan) {
        try {
            Map<String, Object> responseBody = restClient.post()
                    .uri(plan.url())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("query", plan.queryText()))
                    .retrieve()
                    .body(Map.class);

            if (responseBody == null) {
                return SubgraphResult.failed(plan.subgraphName(), "empty response body");
            }

            Map<String, Object> data = (Map<String, Object>) responseBody.getOrDefault("data", Map.of());
            List<Object> errors = (List<Object>) responseBody.getOrDefault("errors", List.of());
            return new SubgraphResult(plan.subgraphName(), data, errors);

        } catch (Exception e) {
            // A real federation gateway has much richer partial-failure semantics (returning
            // null for just the affected fields per GraphQL's error-propagation rules, while
            // still returning everything that DID succeed from other subgraphs). We do a
            // simplified version of that same idea: one subgraph failing doesn't stop the
            // others' results from making it into the final merged response.
            return SubgraphResult.failed(plan.subgraphName(), e.getMessage());
        }
    }
}
