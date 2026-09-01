package com.graphvault.titles.repository;

import com.graphvault.titles.model.Title;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TitleRepository extends JpaRepository<Title, Long> {
}
