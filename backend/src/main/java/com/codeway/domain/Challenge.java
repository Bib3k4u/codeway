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
    @Column(columnDefinition = "text") public String testNamesJson;      // ["name", ...] as the reference run reported them
    /** Same exercise in other languages shares a family: the id of the challenge it was translated from. */
    public Long familyId;
    /**
     * Guided challenges (design patterns): {"quiz": {question, options[{id,text}], answers[], explanation},
     * "steps": [{title, md}], "reveal": {pattern, principle, roles[], summary}}. Test names are prefixed
     * "Guard:" (behaviour that must never break) or "S1:", "S2:"... (the steps). Null for ordinary challenges.
     */
    @Column(columnDefinition = "text") public String guideJson;
    /** Hash of a seeded challenge's content, so an edited seed is re-validated and updated in place. */
    public String contentHash;
    public boolean validated = false;
    public String origin;           // seed | ai
    public Instant createdAt = Instant.now();
}
