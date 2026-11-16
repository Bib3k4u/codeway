package com.codeway.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name = "concept_mastery", uniqueConstraints = @UniqueConstraint(columnNames = {"userId", "language", "concept"}))
public class ConceptMastery {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long userId;
    public String language;
    public String concept;
    public double score = 0.15;
    public int streak = 0;
    public int attemptsCount = 0;
    public boolean mastered = false;
    /** Fully passed graded attempts (nullable: rows from before this was tracked count as 0). */
    public Integer passes;
    public Instant updatedAt = Instant.now();

    public int passCount() { return passes == null ? 0 : passes; }
}
