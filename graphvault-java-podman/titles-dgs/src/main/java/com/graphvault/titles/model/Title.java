package com.graphvault.titles.model;

import jakarta.persistence.*;

/**
 * Plain JPA entity — completely unaware that GraphQL exists. Keeping persistence and the
 * GraphQL schema decoupled (this entity vs. the `type Title` block in schema.graphqls) means
 * either one can change shape independently, the same separation-of-concerns argument that
 * applied to every REST DTO in the earlier StreamVault/SoundVault projects in this series.
 */
@Entity
@Table(name = "titles")
public class Title {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private int releaseYear;

    @Column(length = 2000)
    private String synopsis;

    protected Title() {}

    public Title(String name, int releaseYear, String synopsis) {
        this.name = name;
        this.releaseYear = releaseYear;
        this.synopsis = synopsis;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public int getReleaseYear() { return releaseYear; }
    public String getSynopsis() { return synopsis; }
}
