package com.graphvault.artwork;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The DGS that owns the Artwork type — and the one service in this repo that touches both
 * protocols the blog describes: GraphQL facing outward (toward the gateway), gRPC facing
 * inward (toward asset-service). See ArtworkDataFetcher for where that actually happens.
 */
@SpringBootApplication
public class ArtworkDgsApplication {
    public static void main(String[] args) {
        SpringApplication.run(ArtworkDgsApplication.class, args);
    }
}
