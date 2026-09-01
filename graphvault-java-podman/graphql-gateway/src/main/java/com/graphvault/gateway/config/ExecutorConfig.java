package com.graphvault.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * {@code Executors.newVirtualThreadPerTaskExecutor()} — one line, stable API since Java 21, no
 * preview flags needed. Every task submitted here gets its own cheap virtual thread rather than
 * competing for a slot in a small platform-thread pool.
 *
 * This is the manual, explicit version of what {@code spring.threads.virtual.enabled: true}
 * (set below, and in every DGS's application.yml) does automatically for incoming HTTP request
 * handling: we're deliberately spelling this one out because fanning a single request out into
 * 2-3 parallel downstream calls is exactly the scenario the blog describes field resolvers
 * needing this for — "developers had to reason about thread pools, manage CompletableFutures...
 * most didn't bother unless performance made it unavoidable." With virtual threads, bothering
 * costs almost nothing.
 */
@Configuration
public class ExecutorConfig {

    @Bean
    public ExecutorService fanOutExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
