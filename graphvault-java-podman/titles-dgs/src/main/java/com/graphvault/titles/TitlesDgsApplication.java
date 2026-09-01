package com.graphvault.titles;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A Domain Graph Service (DGS) — Netflix's term, from the blog, for "a Spring Boot application
 * that implements one slice of the overall federated GraphQL schema." This one owns Title:
 * id, name, release year, synopsis.
 *
 * There's very little in this file for the same reason there was very little in every other
 * *Application.java in this whole series: {@code @SpringBootApplication} triggers component
 * scanning and auto-configuration, and the DGS starter (see pom.xml) adds its own
 * auto-configuration on top that discovers every {@code @DgsComponent}-annotated class,
 * matches its data-fetching methods against src/main/resources/schema/schema.graphqls, and
 * wires up a working /graphql endpoint before your own code runs a single line.
 */
@SpringBootApplication
public class TitlesDgsApplication {
    public static void main(String[] args) {
        SpringApplication.run(TitlesDgsApplication.class, args);
    }
}
