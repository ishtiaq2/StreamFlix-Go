package com.graphvault.asset;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A minimal Spring Boot app whose entire job is running a gRPC server — there is no embedded
 * Tomcat, no REST controllers, no GraphQL schema here. This is the "backend service" side of
 * the blog's internal-gRPC picture: something a GraphQL-facing DGS calls, never something a
 * browser, phone, or TV app talks to directly.
 *
 * grpc-server-spring-boot-starter auto-configures a Netty-based gRPC server on the port set by
 * `grpc.server.port` (see application.yml) and registers every {@code @GrpcService}-annotated
 * bean it finds on the classpath as a handler — see {@link AssetGrpcService}.
 */
@SpringBootApplication
public class AssetServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AssetServiceApplication.class, args);
    }
}
