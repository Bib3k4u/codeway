package com.codeway.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name = "lessons", uniqueConstraints = @UniqueConstraint(columnNames = {"language", "concept"}))
public class Lesson {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public String language;
    public String concept;
    @Column(columnDefinition = "text") public String contentMd;
    public String source; // ai | fallback
    /** Prompt version the AI lesson was written with (null: before versions existed). Older ones are written again, once. */
    public Integer version;
    public Instant createdAt = Instant.now();
}
