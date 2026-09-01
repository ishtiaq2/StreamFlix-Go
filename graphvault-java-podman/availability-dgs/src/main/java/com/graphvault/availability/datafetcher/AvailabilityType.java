package com.graphvault.availability.datafetcher;

import com.graphvault.availability.model.Availability;

public record AvailabilityType(String region, boolean available) {
    public static AvailabilityType from(Availability a) {
        return new AvailabilityType(a.getRegion(), a.isAvailable());
    }
}
