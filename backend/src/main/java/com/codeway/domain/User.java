package com.codeway.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name = "users")
public class User {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(unique = true, nullable = false) public String email;
    /** Supabase Auth user id (the JWT "sub"). Sign-in is GitHub through Supabase. */
    @Column(unique = true) public String authSubject;
    /** Legacy column from email/password sign-in; the existing NOT NULL constraint stays, new users get a marker. */
    @Column(nullable = false) public String passwordHash;
    public String displayName;
    public String avatarUrl;
    public Instant createdAt = Instant.now();
    @Column(columnDefinition = "text") public String languageVisitsJson; // {"java": 12, "python": 3}
}
