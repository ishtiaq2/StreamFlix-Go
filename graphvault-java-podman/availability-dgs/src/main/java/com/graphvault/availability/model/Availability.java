package com.graphvault.availability.model;

import jakarta.persistence.*;

@Entity
@Table(name = "availability")
public class Availability {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long titleId;

    @Column(nullable = false)
    private String region; // ISO-ish region code, e.g. "US", "UK", "DE"

    @Column(nullable = false)
    private boolean available;

    protected Availability() {}

    public Availability(Long titleId, String region, boolean available) {
        this.titleId = titleId;
        this.region = region;
        this.available = available;
    }

    public Long getId() { return id; }
    public Long getTitleId() { return titleId; }
    public String getRegion() { return region; }
    public boolean isAvailable() { return available; }
}
