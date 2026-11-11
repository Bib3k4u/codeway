package com.codeway.service;

import com.codeway.ai.AiClient;
import com.codeway.domain.*;
import com.codeway.repo.*;
import com.codeway.runner.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class AttemptService {
    private final AttemptRepo attempts;
    private final ChallengeRepo challenges;
    private final EventRepo events;
    private final ChallengeService challengeService;
    private final MasteryService mastery;
    private final CodeRunner runner;
    private final AiClient ai;
    private final ConceptCatalog catalog;

    public AttemptService(AttemptRepo attempts, ChallengeRepo challenges, EventRepo events, ChallengeService challengeService,
                          MasteryService mastery, CodeRunner runner, AiClient ai, ConceptCatalog catalog) {
        this.attempts = attempts; this.challenges = challenges; this.events = events;
        this.challengeService = challengeService; this.mastery = mastery; this.runner = runner; this.ai = ai; this.catalog = catalog;
    }

    // ---- start or resume ----
    public Map<String, Object> start(Long userId, String language, String concept) {
        catalog.find(language, concept).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown concept"));
        if (!ConceptCatalog.PRACTICE.contains(language))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ConceptCatalog.label(language) + " challenges are coming soon. Practice runs in Java for now.");
        ConceptMastery m = mastery.get(userId, language, concept);

        // Resume unfinished work on this topic, however long ago it was left.
        for (Attempt open : attempts.findByUserIdAndSubmittedAtIsNullOrderByStartedAtDesc(userId)) {
            Challenge ch = challenges.findById(open.challengeId).orElse(null);
            if (ch == null || !ch.language.equals(language) || !ch.concept.equals(concept)) continue;
            Instant now = Instant.now();
            if (open.lastActiveAt != null && now.isAfter(open.lastActiveAt)) // time away is not time spent
                open.startedAt = open.startedAt.plus(Duration.between(open.lastActiveAt, now));
            open.lastActiveAt = now;
            attempts.save(open);
            return startView(open, ch, m, true);
        }

        int difficulty = MasteryService.targetDifficulty(m.score);
        String scaffolding = MasteryService.scaffolding(m.score);
        Challenge ch = challengeService.pick(userId, language, concept, difficulty, scaffolding);
        Attempt a = new Attempt();
        a.userId = userId; a.challengeId = ch.id; a.scoreBefore = m.score; a.lastActiveAt = Instant.now();
        attempts.save(a);
        return startView(a, ch, m, false);
    }

    private Map<String, Object> startView(Attempt a, Challenge ch, ConceptMastery m, boolean resumed) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("attemptId", a.id);
        out.put("resumed", resumed);
        out.put("challenge", Map.of(
                "id", ch.id, "title", ch.title, "descriptionMd", ch.descriptionMd,
                "difficulty", ch.difficulty, "scaffolding", ch.scaffolding, "expectedMinutes", ch.expectedMinutes,
                "files", ChallengeService.starterWithMain(Json.files(ch.starterFilesJson)),
                "testNames", ChallengeService.testNames(ch),
                "sampleOutput", challengeService.sampleOutput(ch)));
        if (resumed) {
            if (a.draftFilesJson != null) out.put("draft", Json.files(a.draftFilesJson));
            out.put("hints", Json.list(a.hintsJson));
            Object last = Json.any(a.lastRunJson);
            if (last != null) out.put("lastRun", last);
            out.put("startedAt", a.startedAt.toString());
        }
        out.put("mastery", masteryView(m));
        return out;
    }

    // ---- draft autosave ----
    public void saveDraft(Long userId, Long attemptId, Map<String, String> files) {
        Attempt a = owned(userId, attemptId);
        if (a.submittedAt != null || files == null || files.isEmpty() || files.size() > 30) return;
        if (files.values().stream().mapToInt(v -> v == null ? 0 : v.length()).sum() > 200_000) return;
        a.draftFilesJson = Json.write(files);
        a.lastActiveAt = Instant.now();
        attempts.save(a);
    }

    // ---- per-topic history and in-progress info ----
    public List<Map<String, Object>> history(Long userId, String language, String concept) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Attempt a : attempts.findByUserIdAndSubmittedAtIsNotNullOrderBySubmittedAtDesc(userId)) {
            Challenge ch = challenges.findById(a.challengeId).orElse(null);
            if (ch == null || !ch.language.equals(language) || !ch.concept.equals(concept)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("title", ch.title); row.put("difficulty", ch.difficulty);
            row.put("passed", a.testsPassed); row.put("total", a.testsTotal);
            row.put("delta", round(a.scoreAfter - a.scoreBefore)); row.put("hints", a.hintsUsed);
            row.put("at", a.submittedAt.toString());
            out.add(row);
            if (out.size() >= 10) break;
        }
        return out;
    }

    public Set<String> openConcepts(Long userId, String language) {
        Set<String> out = new HashSet<>();
        for (Attempt a : attempts.findByUserIdAndSubmittedAtIsNullOrderByStartedAtDesc(userId)) {
            challenges.findById(a.challengeId).filter(c -> c.language.equals(language)).ifPresent(c -> out.add(c.concept));
        }
        return out;
    }

    // ---- run (no mastery update) ----
    public Map<String, Object> run(Long userId, Long attemptId, Map<String, String> files, String modeName) {
        Attempt a = owned(userId, attemptId);
        Challenge ch = challenges.findById(a.challengeId).orElseThrow();
        boolean tests = "tests".equalsIgnoreCase(modeName);
        RunResult r = runner.run(files, tests ? Json.files(ch.testsJson) : Map.of(), tests ? CodeRunner.Mode.TESTS : CodeRunner.Mode.MAIN);
        a.runs++;
        if (!r.compiled()) a.compileErrors++;
        else if (r.runtimeError() || r.timedOut()) a.runtimeErrors++;
        if (tests && r.compiled() && a.firstRunPassed == null) a.firstRunPassed = r.passed();
        Map<String, Object> view = runView(r, a);
        a.lastRunJson = Json.write(view);
        logEvent(a.id, "run", "{\"mode\":\"" + (tests ? "tests" : "main") + "\"}");
        a.draftFilesJson = Json.write(files);
        a.lastActiveAt = Instant.now();
        attempts.save(a);
        return view;
    }

    // ---- submit ----
    /**
     * Grades against the hidden tests. A submission that doesn't pass every test does NOT end the attempt: it is
     * recorded as a failed submission (lowering what the eventual pass earns) and the learner can fix and resubmit.
     * {@code finish=true} ends the attempt with the current result ("move on").
     */
    public Map<String, Object> submit(Long userId, Long attemptId, Map<String, String> files, Integer confidence, boolean finish) {
        Attempt a = owned(userId, attemptId);
        if (a.submittedAt != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "This attempt was already submitted");
        Challenge ch = challenges.findById(a.challengeId).orElseThrow();
        RunResult r = runner.run(files, Json.files(ch.testsJson), CodeRunner.Mode.TESTS);

        int expected = Json.files(ch.testsJson).values().stream().mapToInt(s -> countOf(s, "Check.run(")).sum();
        int total = Math.max(expected, r.total());
        int passed = r.compiled() ? r.passed() : 0;
        a.runs++;
        if (!r.compiled()) a.compileErrors++;
        if (confidence != null) a.selfConfidence = Math.max(1, Math.min(5, confidence));
        if (a.firstRunPassed == null && r.compiled()) a.firstRunPassed = passed;
        boolean allPassed = total > 0 && passed == total;
        int failedBefore = a.failedSubmissions == null ? 0 : a.failedSubmissions;

        if (!allPassed && !finish) {
            a.failedSubmissions = failedBefore + 1;
            a.draftFilesJson = Json.write(files);
            Map<String, Object> view = runView(r, a);
            a.lastRunJson = Json.write(view);
            a.lastActiveAt = Instant.now();
            attempts.save(a);
            logEvent(a.id, "submit_failed", "{\"passed\":" + passed + ",\"total\":" + total + "}");
            Map<String, Object> out = new LinkedHashMap<>(view);
            out.put("final", false);
            out.put("passed", passed);
            out.put("total", total);
            out.put("allPassed", false);
            out.put("failedSubmissions", a.failedSubmissions);
            out.put("feedback", List.of(
                    r.compiled() ? (total - passed) + " of " + total + " hidden tests failed. Each one below shows what it checked and why it failed."
                                 : "Your code didn't compile, so no tests could run. Fix the errors shown in the console.",
                    "Fix your code and submit again. Each extra submission lowers the mastery this challenge can earn, but finishing it still counts."));
            return out;
        }

        a.submittedAt = Instant.now();
        a.testsPassed = passed; a.testsTotal = total;
        a.failedSubmissions = failedBefore;
        a.finalFilesJson = Json.write(files);

        double minutes = Duration.between(a.startedAt, a.submittedAt).toSeconds() / 60.0;
        var signals = new MasteryService.Signals(passed, total, a.runs, a.compileErrors, a.hintsUsed, minutes, ch.expectedMinutes, a.churn, a.selfConfidence, failedBefore);
        var o = mastery.apply(userId, ch.language, ch.concept, ch.difficulty, signals);
        a.performance = o.performance(); a.scoreAfter = o.scoreAfter();
        attempts.save(a);
        logEvent(a.id, "submit", "{\"passed\":" + passed + ",\"total\":" + total + "}");

        Map<String, Object> out = new LinkedHashMap<>(runView(r, a));
        out.put("final", true);
        out.put("failedSubmissions", failedBefore);
        out.put("passed", passed);
        out.put("total", total);
        out.put("allPassed", total > 0 && passed == total);
        out.put("performance", round(o.performance()));
        out.put("scoreBefore", round(o.scoreBefore()));
        out.put("scoreAfter", round(o.scoreAfter()));
        out.put("delta", round(o.scoreAfter() - o.scoreBefore()));
        out.put("streak", o.streak());
        out.put("streakGoal", MasteryService.MASTERY_STREAK);
        out.put("mastered", o.mastered());
        out.put("cleanPass", o.cleanPass());
        out.put("calibration", o.calibration());
        out.put("feedback", feedback(signals, o, passed, total));
        out.put("minutes", round(minutes));
        return out;
    }

    // ---- hints ----
    public Map<String, Object> hint(Long userId, Long attemptId, Map<String, String> files, String lastFailures) {
        Attempt a = owned(userId, attemptId);
        if (a.submittedAt != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "This attempt is already submitted");
        Challenge ch = challenges.findById(a.challengeId).orElseThrow();
        int level = Math.min(4, a.hintsUsed + 1);
        a.hintsUsed = level;
        a.lastActiveAt = Instant.now();
        attempts.save(a);
        Map<String, Object> out = hintView(ch, level, files, lastFailures);
        logEvent(a.id, "hint", "{\"level\":" + level + "}");
        List<Object> shown = Json.list(a.hintsJson);
        shown.add(out);
        a.hintsJson = Json.write(shown);
        attempts.save(a);
        return out;
    }

    private Map<String, Object> hintView(Challenge ch, int level, Map<String, String> files, String lastFailures) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("level", level);
        out.put("maxLevel", 4);
        if (level == 4) {
            out.put("kind", "solution");
            out.put("text", "Here is a complete solution. Study it, then try to rebuild it from memory. This attempt will earn very little mastery.");
            out.put("files", Json.files(ch.referenceSolutionJson));
            out.put("cost", "Viewing the solution caps this attempt's score.");
            return out;
        }
        String[] kinds = {"nudge", "concept", "code"};
        out.put("kind", kinds[level - 1]);
        out.put("text", hintText(ch, level, files, lastFailures));
        out.put("cost", level == 1 ? "Small mastery cost" : level == 2 ? "Medium mastery cost" : "Large mastery cost");
        return out;
    }

    private String hintText(Challenge ch, int level, Map<String, String> files, String failures) {
        List<String> stored = Json.strings(ch.hintsJson);
        if (ai.isConfigured()) {
            try {
                String levelRule = switch (level) {
                    case 1 -> "Give a NUDGE: one short guiding question that redirects the learner's thinking. Do not reveal the answer or write code.";
                    case 2 -> "Give a CONCEPTUAL hint: explain the OOP idea that unlocks the problem in 2-4 sentences. No code.";
                    default -> "Give PARTIAL CODE: a small Java snippet (max 8 lines) for the single most blocking step. Do not provide the whole solution.";
                };
                StringBuilder sb = new StringBuilder("Challenge:\n").append(ch.descriptionMd).append("\n\nLearner's current files:\n");
                if (files != null) files.forEach((k, v) -> sb.append("--- ").append(k).append("\n").append(v, 0, Math.min(v.length(), 2500)).append("\n"));
                if (failures != null && !failures.isBlank()) sb.append("\nLatest failing tests:\n").append(failures, 0, Math.min(failures.length(), 1200));
                return ai.chat(ai.hintModel(),
                        "You are a patient Java tutor giving one hint at a time. Markdown allowed. Be brief and encouraging. " + levelRule,
                        sb.toString(), false).trim();
            } catch (Exception e) {
                System.err.println("[hint] AI failed, using stored hint: " + e.getMessage());
            }
        }
        if (level - 1 < stored.size()) return stored.get(level - 1);
        return switch (level) {
            case 1 -> "Re-read the requirements. Which one does your code not satisfy yet, and what would the object need to do differently?";
            case 2 -> "Think about which class should own this behaviour, and what the OOP concept in this lesson says about where logic belongs.";
            default -> "Try writing the smallest piece that makes a single failing test pass, then repeat.";
        };
    }

    // ---- telemetry ----
    public record Event(Long attemptId, String type, JsonNode payload) {}

    public void telemetry(Long userId, List<Event> batch) {
        for (Event e : batch) {
            Attempt a = owned(userId, e.attemptId());
            EventLog log = new EventLog();
            log.attemptId = a.id; log.type = e.type();
            log.payloadJson = e.payload() == null ? "{}" : e.payload().toString();
            events.save(log);
            if (a.submittedAt != null) continue;
            switch (e.type()) {
                case "first_keystroke" -> { if (a.firstKeystrokeMs == null) a.firstKeystrokeMs = e.payload() == null ? null : e.payload().path("ms").asLong(); }
                case "churn" -> a.churn++;
                default -> {}
            }
            attempts.save(a);
        }
    }

    // ---- views ----
    public Map<String, Object> masteryView(ConceptMastery m) {
        return Map.of("score", round(m.score), "streak", m.streak, "mastered", m.mastered,
                "streakGoal", MasteryService.MASTERY_STREAK, "attempts", m.attemptsCount);
    }

    private Map<String, Object> runView(RunResult r, Attempt a) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("compiled", r.compiled());
        o.put("diagnostics", r.diagnostics());
        o.put("stdout", r.stdout());
        o.put("stderr", r.stderr());
        o.put("tests", r.tests());
        o.put("timedOut", r.timedOut());
        o.put("exitCode", r.exitCode());
        o.put("millis", r.millis());
        o.put("runs", a.runs);
        return o;
    }

    private static List<String> feedback(MasteryService.Signals s, MasteryService.Outcome o, int passed, int total) {
        List<String> f = new ArrayList<>();
        if (total > 0 && passed == total) f.add("All " + total + " tests passed" + (s.failedSubmissions() > 0 ? " after " + s.failedSubmissions() + " earlier " + (s.failedSubmissions() == 1 ? "try" : "tries") + "." : "."));
        else f.add(passed + " of " + total + " tests passed. You moved on, so this result is final. The failing tests are listed below.");
        if (s.failedSubmissions() > 0 && total > 0 && passed == total)
            f.add("Needing to resubmit lowered the mastery earned, and it doesn't count toward your clean-pass streak.");
        if (s.hintLevel() >= 4) f.add("You viewed the full solution, so this attempt earns little mastery. Rebuild it from memory next time.");
        else if (s.hintLevel() > 0) f.add("You used hints up to level " + s.hintLevel() + ". A hint-free pass is needed to build your streak.");
        else if (total > 0 && passed == total) f.add("No hints used. Nice.");
        if (s.compileErrors() > 2) f.add(s.compileErrors() + " compile errors: try reading the first error carefully before running again.");
        switch (o.calibration()) {
            case "overconfident" -> f.add("You felt more confident than the result showed. Test edge cases before submitting.");
            case "underconfident" -> f.add("You did better than you expected. Trust your reasoning a bit more.");
            default -> {}
        }
        if (o.mastered()) f.add("Concept mastered. You passed " + MasteryService.MASTERY_STREAK + " challenges in a row at your level without hints.");
        else if (o.cleanPass()) f.add("Clean pass: streak " + o.streak() + " of " + MasteryService.MASTERY_STREAK + ".");
        return f;
    }

    /** Server-side activity log: feeds the streak calendar. */
    private void logEvent(Long attemptId, String type, String payload) {
        EventLog e = new EventLog();
        e.attemptId = attemptId; e.type = type; e.payloadJson = payload;
        events.save(e);
    }

    private Attempt owned(Long userId, Long attemptId) {
        Attempt a = attempts.findById(attemptId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Attempt not found"));
        if (!a.userId.equals(userId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Attempt not found");
        return a;
    }

    private static int countOf(String s, String n) { int c = 0, i = 0; while ((i = s.indexOf(n, i)) >= 0) { c++; i += n.length(); } return c; }
    public static double round(double d) { return Math.round(d * 1000.0) / 1000.0; }
}
