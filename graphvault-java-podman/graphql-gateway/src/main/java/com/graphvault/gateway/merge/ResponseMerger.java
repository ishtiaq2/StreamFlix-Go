package com.graphvault.gateway.merge;

import com.graphvault.gateway.client.SubgraphResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Combines N independent subgraph responses into the single {@code {"data": {...}, "errors":
 * [...]}} shape a GraphQL client expects back from ONE query. Because our {@link
 * com.graphvault.gateway.planner.QueryPlanner} only ever groups DISTINCT top-level fields per
 * subgraph, merging is just a union of each subgraph's "data" map — there's no need to
 * recursively merge overlapping keys, which is the much harder problem a real federated
 * gateway solves when two subgraphs contribute fields to the SAME object type.
 */
@Component
public class ResponseMerger {

    public Map<String, Object> merge(List<SubgraphResult> results) {
        Map<String, Object> combinedData = new LinkedHashMap<>();
        List<Object> combinedErrors = new ArrayList<>();

        for (SubgraphResult result : results) {
            combinedData.putAll(result.data());
            combinedErrors.addAll(result.errors());
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("data", combinedData);
        if (!combinedErrors.isEmpty()) {
            response.put("errors", combinedErrors);
        }
        return response;
    }
}
