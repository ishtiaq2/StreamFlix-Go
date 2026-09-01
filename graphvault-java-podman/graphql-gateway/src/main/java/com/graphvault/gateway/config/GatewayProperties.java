package com.graphvault.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Binds the `graphvault.*` tree from application.yml. Two maps do all the "routing" work in
 * this whole gateway:
 *
 * - {@code subgraphs}: subgraph name -> its /graphql URL
 * - {@code fieldOwners}: top-level query field name -> which subgraph name owns it
 *
 * In a real federated setup, this mapping isn't hand-maintained config at all — it's derived
 * automatically by composing every subgraph's published schema (each subgraph literally
 * declares which fields/types it owns via `@key`/`@extends` directives), and a gateway-side
 * composition step builds the equivalent of these two maps for you, then keeps it in sync as
 * subgraphs deploy independently. Hardcoding it here keeps the Java approachable, at the cost
 * of needing a manual update to this YAML any time a DGS adds a new top-level field.
 */
@ConfigurationProperties(prefix = "graphvault")
public record GatewayProperties(Map<String, String> subgraphs, Map<String, String> fieldOwners) {
}
