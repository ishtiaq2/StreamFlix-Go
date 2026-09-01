package com.graphvault.gateway.controller;

import java.util.Map;

/**
 * The standard GraphQL-over-HTTP request shape. Note: this teaching gateway does NOT support
 * the {@code variables} map — arguments must be inlined as literals directly in the query text
 * (e.g. {@code title(id: "1")} rather than {@code title(id: $id)}). Supporting variables would
 * mean substituting them into each re-printed sub-query correctly, which is easy to get wrong
 * silently; leaving it out entirely was the more honest option for a from-scratch teaching
 * implementation. Real GraphQL clients and every DGS in this repo DO support variables — it's
 * only this hand-rolled gateway layer that doesn't.
 */
public record GraphQLRequest(String query, Map<String, Object> variables, String operationName) {
}
