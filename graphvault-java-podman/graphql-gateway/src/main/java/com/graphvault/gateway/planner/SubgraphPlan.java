package com.graphvault.gateway.planner;

/** One subgraph's worth of work for a single incoming request: which subgraph, where to send
 * it, and the (re-printed, minimal) GraphQL query text containing only that subgraph's fields. */
public record SubgraphPlan(String subgraphName, String url, String queryText) {
}
