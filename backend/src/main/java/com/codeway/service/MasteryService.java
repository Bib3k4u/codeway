package com.codeway.service;

import com.codeway.domain.*;
import com.codeway.repo.MasteryRepo;
import org.springframework.stereotype.Service;
import java.time.Instant;

/** All mastery math lives here. The LLM never decides confidence. */
@Service
public class MasteryService {
    public static final double K = 0.30;
    public static final int MASTERY_STREAK = 3;
    public static final double INITIAL_SCORE = 0.15;

    public record Signals(int testsPassed, int testsTotal, int runs, int compileErrors, int hintLevel,
                          double elapsedMinutes, int expectedMinutes, int churn, Integer confidence, int failedSubmissions) {}

    public record Outcome(double performance, double expected, double scoreBefore, double scoreAfter,
                          int streak, boolean mastered, boolean cleanPass, String calibration) {}

    private final MasteryRepo repo;
    public MasteryService(MasteryRepo repo) { this.repo = repo; }

    public ConceptMastery get(Long userId, String language, String concept) {
        return repo.findByUserIdAndLanguageAndConcept(userId, language, concept).orElseGet(() -> {
            ConceptMastery m = new ConceptMastery();
            m.userId = userId; m.language = language; m.concept = concept;
            m.score = INITIAL_SCORE;
            return m;
        });
    }

    /** Difficulty (1..5) the learner should currently be working at. */
    public static int targetDifficulty(double score) {
        return Math.max(1, Math.min(5, (int) Math.ceil(score * 5)));
    }

    public static String scaffolding(double score) {
        return score < 0.35 ? "HEAVY" : score < 0.70 ? "PARTIAL" : "BLANK";
    }

    static double difficultyRating(int difficulty) { return 0.1 + (difficulty - 1) * 0.2; }

    public static double performance(Signals s) {
        double ratio = s.testsTotal() == 0 ? 0 : (double) s.testsPassed() / s.testsTotal();
        double perf;
        if (ratio < 1.0) {
            perf = ratio * 0.6;
        } else {
            perf = 1.0;
            perf -= Math.min(0.15, 0.05 * Math.max(0, s.runs() - 3));
            perf -= Math.min(0.10, 0.02 * s.compileErrors());
            perf -= Math.min(0.10, 0.02 * s.churn());
            double over = s.elapsedMinutes() / Math.max(1, s.expectedMinutes());
            if (over > 2.0) perf -= 0.20; else if (over > 1.5) perf -= 0.10;
            perf -= Math.min(0.30, 0.10 * s.failedSubmissions()); // passing only after fixing failed submissions
            switch (s.hintLevel()) {
                case 0 -> {}
                case 1 -> perf -= 0.08;
                case 2 -> perf -= 0.20;
                case 3 -> perf -= 0.40;
                default -> perf = Math.min(perf, 0.20); // full solution costs mastery points
            }
        }
        if (s.confidence() != null && s.confidence() >= 4 && ratio < 1.0) perf -= 0.05; // overconfident
        return Math.max(0, Math.min(1, perf));
    }

    public static String calibration(Integer confidence, int passed, int total) {
        if (confidence == null || total == 0) return "unrated";
        double c = (confidence - 1) / 4.0, r = (double) passed / total;
        if (c - r > 0.35) return "overconfident";
        if (r - c > 0.45) return "underconfident";
        return "well-calibrated";
    }

    /** Apply a graded attempt to the stored mastery row. */
    public Outcome apply(Long userId, String language, String concept, int challengeDifficulty, Signals s) {
        ConceptMastery m = get(userId, language, concept);
        double before = m.score;
        int targetBefore = targetDifficulty(before);
        double perf = performance(s);
        double expected = 1.0 / (1.0 + Math.exp(-8.0 * (before - difficultyRating(challengeDifficulty))));
        double after = Math.max(0, Math.min(1, before + K * (perf - expected)));

        boolean full = s.testsTotal() > 0 && s.testsPassed() == s.testsTotal();
        boolean clean = full && s.hintLevel() == 0 && s.failedSubmissions() == 0;
        boolean atTarget = challengeDifficulty >= targetBefore;
        if (!clean) m.streak = 0; // failed, hinted, or needed resubmitting
        else if (clean && atTarget) m.streak++;
        // clean pass below target: hold the streak (no credit, no reset)

        m.score = after;
        m.attemptsCount++;
        if (m.streak >= MASTERY_STREAK) m.mastered = true;
        m.updatedAt = Instant.now();
        repo.save(m);
        return new Outcome(perf, expected, before, after, m.streak, m.mastered, clean && atTarget,
                calibration(s.confidence(), s.testsPassed(), s.testsTotal()));
    }
}
