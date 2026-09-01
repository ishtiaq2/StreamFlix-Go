package com.graphvault.gateway.client;

import java.util.List;
import java.util.Map;

public record SubgraphResult(String subgraphName, Map<String, Object> data, List<Object> errors) {

    public static SubgraphResult failed(String subgraphName, String message) {
        return new SubgraphResult(subgraphName, Map.of(),
                List.of(Map.of("message", "Call to " + subgraphName + " failed: " + message)));
    }
}
