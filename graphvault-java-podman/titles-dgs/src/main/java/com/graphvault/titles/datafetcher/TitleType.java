package com.graphvault.titles.datafetcher;

import com.graphvault.titles.model.Title;

/**
 * The shape returned to GraphQL callers. Note {@code id} is a String here even though the JPA
 * entity's primary key is a {@code Long} — GraphQL's {@code ID} scalar is serialized as a
 * string over the wire, and mapping explicitly here (rather than exposing the entity's Long
 * directly) keeps that scalar-coercion detail in one obvious place instead of relying on
 * graphql-java's automatic coercion rules.
 */
public record TitleType(String id, String name, int releaseYear, String synopsis) {
    public static TitleType from(Title t) {
        return new TitleType(String.valueOf(t.getId()), t.getName(), t.getReleaseYear(), t.getSynopsis());
    }
}
