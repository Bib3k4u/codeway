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
    public Instant createdAt = Instant.now();
}
