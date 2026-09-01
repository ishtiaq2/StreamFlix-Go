package com.graphvault.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The one thing every client (browser, mobile app, this repo's future TV app, whatever) talks
 * to. Everything downstream of this class — titles-dgs, artwork-dgs, availability-dgs — is
 * invisible to callers; they see one GraphQL schema's worth of fields at one URL.
 *
 * Real Netflix's equivalent gateway composes a "supergraph" from every subgraph's published SDL
 * at deploy time (via Apollo Federation's composition step) and executes a real query plan
 * against it. This class instead hardcodes which fields live where — see
 * {@link com.graphvault.gateway.config.GatewayProperties} and
 * docs/ARCHITECTURE_VS_BLOG.md for exactly what that trades away.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class GraphqlGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GraphqlGatewayApplication.class, args);
    }
}
