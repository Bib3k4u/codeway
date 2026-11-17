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
    /**
     * A concept is mastered by either of two routes, so needing a hint now and then can never trap a learner in a loop:
     * <ol>
     *   <li><b>Streak:</b> {@code MASTERY_STREAK} clean passes in a row (no hints, no resubmits) at or above their level.</li>
     *   <li><b>Score:</b> the mastery score (which already weighs every challenge's difficulty, the help taken and the time) has
     *       reached {@code MASTERY_SCORE}, backed by at least {@code MASTERY_MIN_PASSES} full passes, and the attempt that
     *       crosses the line is itself a solid pass ({@code SOLID_PASS} or better, so at most a light hint) on a hard
     *       challenge ({@code SCORE_ROUTE_MIN_DIFFICULTY} or above). A lucky guess, an easy challenge or a pass that leaned on the
     *       solution cannot get there.</li>
     * </ol>
     */
    public static final int MASTERY_STREAK = 3;
    public static final double MASTERY_SCORE = 0.85;
    public static final int MASTERY_MIN_PASSES = 3;
    public static final double SOLID_PASS = 0.70;
    public static final int SCORE_ROUTE_MIN_DIFFICULTY = 4;
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

    /** The score route to mastery (see {@link #MASTERY_SCORE}): judged right after an attempt is graded. */
    public static boolean masteredByScore(double scoreAfter, int passes, int challengeDifficulty, double performance, boolean fullPass) {
        return fullPass && scoreAfter >= MASTERY_SCORE && passes >= MASTERY_MIN_PASSES
                && challengeDifficulty >= SCORE_ROUTE_MIN_DIFFICULTY && performance >= SOLID_PASS;
    }

    /**
     * How far along to mastery, 0..1: whichever route is closer, the streak or the score. Short of mastery it never reads
     * 100%, so a full bar always means mastered.
     */
    public static double progress(ConceptMastery m) {
        if (m.mastered) return 1.0;
        if (m.attemptsCount <= 0) return 0.0;
        double byStreak = Math.min(1.0, (double) m.streak / MASTERY_STREAK);
        double byScore = Math.min(1.0, m.score / MASTERY_SCORE);
        return Math.min(0.99, Math.max(byStreak, byScore));
    }

    /** Difficulty (1..5) the learner should currently be working at. */
    public static int targetDifficulty(double score) {
        return Math.max(1, Math.min(5, (int) Math.ceil(score * 5)));
    }

    /**
     * How much of the starter is written for the learner. The first step down comes after one decent pass (a clean pass or a light hint
     * lifts the score past {@link #HEAVY_BELOW}), so the second challenge is visibly less hand-held than the first; leaning on the
     * full solution does not.
     */
    public static final double HEAVY_BELOW = 0.22;
    public static String scaffolding(double score) {
        return score < HEAVY_BELOW ? "HEAVY" : score < 0.70 ? "PARTIAL" : "BLANK";
    }

    /** The score a learner would have after a clean pass of a challenge at this difficulty: used to prepare their next challenge ahead. */
    public static double scoreAfterCleanPass(double before, int difficulty) {
        double expected = 1.0 / (1.0 + Math.exp(-8.0 * (before - difficultyRating(difficulty))));
        return nextScore(before, 1.0, expected, true);
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
        if (full) m.passes = m.passCount() + 1;
        if (m.streak >= MASTERY_STREAK || masteredByScore(after, m.passCount(), challengeDifficulty, perf, full)) m.mastered = true;
        m.updatedAt = Instant.now();
        repo.save(m);
        return new Outcome(perf, expected, before, after, m.streak, m.mastered, clean && atTarget, flu, fluencyLabel(flu));
    }
}
