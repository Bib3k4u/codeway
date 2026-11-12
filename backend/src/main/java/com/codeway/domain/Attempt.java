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
    public Integer selfConfidence;  // legacy self-rating (no longer asked)
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
    /** Redo of an earlier challenge from the learner's history: graded, but mastery is not changed. */
    public Boolean practice;
    @Column(columnDefinition = "text") public String revealedJson;     // indexes of hidden tests the learner revealed
    @Column(columnDefinition = "text") public String lastTestsJson;    // last test results, unmasked (server only)

    public boolean isPractice() { return Boolean.TRUE.equals(practice); }
}
