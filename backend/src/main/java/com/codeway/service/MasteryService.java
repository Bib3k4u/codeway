package com.codeway.service;

import com.codeway.domain.*;
import com.codeway.repo.MasteryRepo;
import org.springframework.stereotype.Service;
import java.time.Instant;

/**
 * All mastery math lives here. The LLM never decides it, and the learner isn't asked to rate themselves: how
 * fluently they worked is read from what they did (see {@link #fluency}).
 *
 * <p>Score update is Elo-like ({@code K * (performance - expected)}), with two guarantees: a full pass never lowers
 * the score, and practice attempts (redoing an old challenge) never change it.
 */
@Service
public class MasteryService {
    public static final double K = 0.30;
    public static final int MASTERY_STREAK = 3;
    public static final double INITIAL_SCORE = 0.15;

    /**
     * What happened in an attempt.
     * @param runs           program and test runs before the final submit (submits are not runs)
     * @param firstTestRatio share of tests passing on the learner's first test run (null if they never ran tests)
     * @param reveals        hidden tests the learner chose to reveal
     */
    public record Signals(int testsPassed, int testsTotal, int runs, int compileErrors, int hintLevel,
                          double elapsedMinutes, int expectedMinutes, Double firstTestRatio, int failedSubmissions, int reveals) {}

    /** @param fluency 0..1 estimate of how comfortably the learner worked; {@code fluencyLabel} is its name */
    public record Outcome(double performance, double expected, double scoreBefore, double scoreAfter,
                          int streak, boolean mastered, boolean cleanPass, double fluency, String fluencyLabel) {}

    private final MasteryRepo repo;
    public MasteryService(MasteryRepo repo) { this.repo = repo; }

    public ConceptMastery get(Long userId, String language, String concept) {
        return repo.findByUserIdAndLanguageAndConcept(userId, language, concept).orElseGet(() -> blank(userId, language, concept));
    }

    /** A concept the learner hasn't started: nothing is stored yet, so building it needs no database lookup. */
    public ConceptMastery blank(Long userId, String language, String concept) {
        ConceptMastery m = new ConceptMastery();
        m.userId = userId; m.language = language; m.concept = concept;
        m.score = INITIAL_SCORE;
        return m;
    }

    /** Difficulty (1..5) the learner should currently be working at. */
    public static int targetDifficulty(double score) {
        return Math.max(1, Math.min(5, (int) Math.ceil(score * 5)));
    }

    public static String scaffolding(double score) {
        return score < 0.35 ? "HEAVY" : score < 0.70 ? "PARTIAL" : "BLANK";
    }

    static double difficultyRating(int difficulty) { return 0.1 + (difficulty - 1) * 0.2; }

    /**
     * 0..1 quality of the attempt. A partial result scales with the share of tests passed. A full pass starts at 1
     * and loses only for help taken (hints, revealed tests, resubmissions) and for clear struggle (many runs or
     * compile errors beyond normal iteration, or taking far longer than expected). Normal trial and error is free.
     */
    public static double performance(Signals s) {
        double ratio = s.testsTotal() == 0 ? 0 : (double) s.testsPassed() / s.testsTotal();
        if (ratio < 1.0) return Math.max(0, ratio * 0.6 - Math.min(0.1, 0.03 * s.reveals()));
        double perf = 1.0;
        switch (s.hintLevel()) {
            case 0 -> {}
            case 1 -> perf -= 0.08;
            case 2 -> perf -= 0.20;
            case 3 -> perf -= 0.40;
            default -> perf = Math.min(perf, 0.20); // the full solution caps the attempt
        }
        perf -= Math.min(0.20, 0.06 * s.reveals());
        perf -= Math.min(0.30, 0.10 * s.failedSubmissions());
        perf -= Math.min(0.08, 0.01 * Math.max(0, s.runs() - 8));
        perf -= Math.min(0.06, 0.015 * Math.max(0, s.compileErrors() - 4));
        double over = s.elapsedMinutes() / Math.max(1, s.expectedMinutes());
        if (over > 2.5) perf -= 0.08; else if (over > 1.75) perf -= 0.04;
        return Math.max(0, Math.min(1, perf));
    }

    /**
     * How comfortably the learner worked, from behaviour rather than a self-rating: how much of the suite passed on
     * their first test run, how few runs they needed, and pace against the expected time.
     */
    public static double fluency(Signals s) {
        double first = s.firstTestRatio() == null ? 0.5 : s.firstTestRatio();
        double runs = 1 - Math.min(1, Math.max(0, s.runs() - 1) / 10.0);
        double over = s.elapsedMinutes() / Math.max(1, s.expectedMinutes());
        double pace = over <= 1 ? 1 : over <= 2 ? 0.5 : 0.15;
        double help = s.hintLevel() == 0 && s.reveals() == 0 ? 1 : 0.4;
        return Math.max(0, Math.min(1, 0.4 * first + 0.2 * runs + 0.2 * pace + 0.2 * help));
    }

    public static String fluencyLabel(double f) { return f >= 0.75 ? "fluent" : f >= 0.45 ? "steady" : "effortful"; }

    /** Score change: Elo-like, but a full pass always earns something and a miss loses at most 0.10. */
    static double nextScore(double before, double perf, double expected, boolean full) {
        double raw = K * (perf - expected);
        double delta = full ? Math.max(raw, 0.01 + 0.03 * perf * (1 - before)) : Math.max(-0.10, raw);
        return Math.max(0, Math.min(1, before + delta));
    }

    public static double nextScoreForTest(double before, double perf, double expected, boolean full) { return nextScore(before, perf, expected, full); }

    /** Apply a graded attempt to the stored mastery row. Practice attempts are graded but change nothing. */
    public Outcome apply(Long userId, String language, String concept, int challengeDifficulty, Signals s, boolean practice) {
        ConceptMastery m = get(userId, language, concept);
        double before = m.score;
        int targetBefore = targetDifficulty(before);
        double perf = performance(s);
        double flu = fluency(s);
        double expected = 1.0 / (1.0 + Math.exp(-8.0 * (before - difficultyRating(challengeDifficulty))));
        boolean full = s.testsTotal() > 0 && s.testsPassed() == s.testsTotal();
        boolean clean = full && s.hintLevel() == 0 && s.failedSubmissions() == 0 && s.reveals() == 0;
        boolean atTarget = challengeDifficulty >= targetBefore;
        if (practice) return new Outcome(perf, expected, before, before, m.streak, m.mastered, false, flu, fluencyLabel(flu));

        double after = nextScore(before, perf, expected, full);
        if (!clean) m.streak = 0; // failed, helped, or needed resubmitting
        else if (atTarget) m.streak++;
        // clean pass below target: hold the streak (no credit, no reset)

        m.score = after;
        m.attemptsCount++;
        if (m.streak >= MASTERY_STREAK) m.mastered = true;
        m.updatedAt = Instant.now();
        repo.save(m);
        return new Outcome(perf, expected, before, after, m.streak, m.mastered, clean && atTarget, flu, fluencyLabel(flu));
    }
}
