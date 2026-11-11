package com.codeway.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name = "attempts")
public class Attempt {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long userId;
    public Long challengeId;
    public Instant startedAt = Instant.now();
    public Instant submittedAt;
    public int runs;
    public int compileErrors;
    public int runtimeErrors;
    public int hintsUsed;           // highest hint level reached
    public int testsPassed;
    public int testsTotal;
    public Integer firstRunPassed;  // tests passed on first graded run
    public Integer selfConfidence;  // 1..5
    public Long firstKeystrokeMs;
    public int churn;
    public Integer failedSubmissions;  // submissions that did not pass every test (nullable for existing rows)               // large delete-and-rewrite bursts
    public double performance;
    public double scoreBefore;
    public double scoreAfter;
    @Column(columnDefinition = "text") public String finalFilesJson;
    @Column(columnDefinition = "text") public String draftFilesJson;   // work in progress, saved continuously
    @Column(columnDefinition = "text") public String hintsJson;        // hints already shown (list of objects)
    @Column(columnDefinition = "text") public String lastRunJson;      // last run/test output for the console
    public Instant lastActiveAt;
}
