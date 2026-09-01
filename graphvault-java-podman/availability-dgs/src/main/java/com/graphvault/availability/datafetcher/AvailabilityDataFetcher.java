package com.graphvault.availability.datafetcher;

import com.graphvault.availability.repository.AvailabilityRepository;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;

import java.util.List;

@DgsComponent
public class AvailabilityDataFetcher {

    private final AvailabilityRepository availabilityRepository;

    public AvailabilityDataFetcher(AvailabilityRepository availabilityRepository) {
        this.availabilityRepository = availabilityRepository;
    }

    @DgsQuery
    public List<AvailabilityType> availabilityForTitle(@InputArgument Long titleId) {
        return availabilityRepository.findByTitleId(titleId).stream()
                .map(AvailabilityType::from)
                .toList();
    }
}
