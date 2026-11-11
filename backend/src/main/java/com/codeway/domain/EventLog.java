package com.codeway.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name = "events")
public class EventLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long attemptId;
    public String type;
    @Column(columnDefinition = "text") public String payloadJson;
    public Instant ts = Instant.now();
}
