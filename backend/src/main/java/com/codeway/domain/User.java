package com.codeway.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name = "users")
public class User {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(unique = true, nullable = false) public String email;
    @Column(nullable = false) public String passwordHash;
    public String displayName;
    public Instant createdAt = Instant.now();
    @Column(columnDefinition = "text") public String languageVisitsJson; // {"java": 12, "python": 3}
}
