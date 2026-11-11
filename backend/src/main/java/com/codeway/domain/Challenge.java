package com.codeway.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name = "challenges")
public class Challenge {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public String language;
    public String concept;
    public int difficulty;          // 1..5
    public String scaffolding;      // HEAVY | PARTIAL | BLANK
    public String title;
    public int expectedMinutes = 10;
    @Column(columnDefinition = "text") public String descriptionMd;
    @Column(columnDefinition = "text") public String starterFilesJson;   // {path: content}
    @Column(columnDefinition = "text") public String testsJson;          // {path: content} hidden
    @Column(columnDefinition = "text") public String referenceSolutionJson;
    @Column(columnDefinition = "text") public String hintsJson;          // optional stored hints [l1,l2,l3]
    @Column(columnDefinition = "text") public String sampleOutput;       // stdout of the reference Main
    public boolean validated = false;
    public String origin;           // seed | ai
    public Instant createdAt = Instant.now();
}
