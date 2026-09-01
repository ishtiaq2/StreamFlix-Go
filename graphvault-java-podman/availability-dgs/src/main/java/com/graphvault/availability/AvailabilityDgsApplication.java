package com.graphvault.availability;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The third and simplest DGS in this repo — deliberately so. Once titles-dgs has taught you
 * "a DGS is a Spring Boot app with a schema file and @DgsComponent classes" and artwork-dgs has
 * taught you "a DGS can call other protocols internally," this one exists mainly so the
 * gateway has three real subgraphs to fan a single query out to, matching the blog's own
 * example: "fetching titles and images for five shows... contacts 2-3 DGSs to resolve fields
 * like metadata, artwork, and availability."
 */
@SpringBootApplication
public class AvailabilityDgsApplication {
    public static void main(String[] args) {
        SpringApplication.run(AvailabilityDgsApplication.class, args);
    }
}
