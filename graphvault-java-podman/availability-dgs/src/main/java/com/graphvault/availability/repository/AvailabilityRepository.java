package com.graphvault.availability.repository;

import com.graphvault.availability.model.Availability;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AvailabilityRepository extends JpaRepository<Availability, Long> {
    List<Availability> findByTitleId(Long titleId);
}
