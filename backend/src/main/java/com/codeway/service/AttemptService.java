package com.codeway.service;

import com.codeway.ai.AiClient;
import com.codeway.domain.*;
import com.codeway.repo.*;
import com.codeway.runner.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
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
    /** Hints per learner per rolling hour. */
    @Value("${codeway.hints.per-hour:5}") private int hintsPerHour = 5;

    public AttemptService(AttemptRepo attempts, ChallengeRepo challenges, EventRepo events, ChallengeService challengeService,
                          MasteryService mastery, CodeRunner runner, AiClient ai, ConceptCatalog catalog) {
        this.attempts = attempts; this.challenges = challenges; this.events = events;
        this.challengeService = challengeService; this.mastery = mastery; this.runner = runner; this.ai = ai; this.catalog = catalog;
    }

    // ---- start or resume ----
    public Map<String, Object> start(Long userId, String language, String concept) {
        var info = catalog.find(language, concept).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown concept"));
        if (!catalog.practiceOpen(language, concept)) {
            String track = catalog.track(info.track()).map(ConceptCatalog.Track::title).orElse("This track");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Practice for " + track + " is coming soon. Read the lesson for now.");
        }
        ConceptMastery m = mastery.get(userId, language, concept);
        List<Attempt> open = attempts.findByUserIdAndSubmittedAtIsNullOrderByStartedAtDesc(userId);
        Map<Long, Challenge> chOf = new HashMap<>();
        Map<Long, Challenge> openChallenges = ChallengeService.byId(challenges, open.stream().map(o -> o.challengeId).toList());
        for (Attempt o : open) { Challenge c = openChallenges.get(o.challengeId); if (c != null) chOf.put(o.id, c); }

        // The question the learner is on for this concept is the most recently active open attempt, in any language.
        // Switching language keeps that question: resume it in the new language, or translate it.
        Attempt current = open.stream().filter(o -> chOf.containsKey(o.id) && chOf.get(o.id).concept.equals(concept))
                .max(Comparator.comparing(AttemptService::lastActive)).orElse(null);
        if (current != null) {
            Challenge cur = chOf.get(current.id);
            if (cur.language.equals(language)) return resume(current, cur, m);
            long fam = ChallengeService.family(cur);
            for (Attempt o : open) {
                Challenge c = chOf.get(o.id);
                if (c != null && c.language.equals(language) && c.concept.equals(concept) && ChallengeService.family(c) == fam) return resume(o, c, m);
            }
            Challenge same = challengeService.inLanguage(cur, language);
            if (same != null) {
                Attempt moved = newAttempt(userId, same, m, current.isPractice());
                moved.quizTries = current.quizTries; moved.quizSolved = current.quizSolved;     // the diagnosis carries over
                attempts.save(moved);
                return startView(moved, same, m, false);
            }
            // no translation possible (e.g. AI unavailable): fall back to this language's own question
        }
        for (Attempt o : open) {
            Challenge c = chOf.get(o.id);
            if (c != null && c.language.equals(language) && c.concept.equals(concept)) return resume(o, c, m);
        }

        int difficulty = MasteryService.targetDifficulty(m.score);
        String scaffolding = MasteryService.scaffolding(m.score);
        Challenge ch = challengeService.pick(userId, language, concept, difficulty, scaffolding);
        return startView(newAttempt(userId, ch, m, false), ch, m, false);
    }

    /** Redo a challenge from the learner's history as practice: graded, but mastery doesn't change. */
    public Map<String, Object> redo(Long userId, Long challengeId) {
        Challenge ch = challenges.findById(challengeId).filter(c -> c.validated)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Challenge not found"));
        ConceptMastery m = mastery.get(userId, ch.language, ch.concept);
        for (Attempt o : attempts.findByUserIdAndSubmittedAtIsNullOrderByStartedAtDesc(userId))
            if (o.challengeId.equals(challengeId)) return resume(o, ch, m);
        return startView(newAttempt(userId, ch, m, true), ch, m, false);
    }

    private Attempt newAttempt(Long userId, Challenge ch, ConceptMastery m, boolean practice) {
        Attempt a = new Attempt();
        a.userId = userId; a.challengeId = ch.id; a.scoreBefore = m.score; a.lastActiveAt = Instant.now();
        a.practice = practice ? Boolean.TRUE : null;
        return attempts.save(a);
    }

    /** Pick up unfinished work however long ago it was left; time away is not time spent. */
    private Map<String, Object> resume(Attempt open, Challenge ch, ConceptMastery m) {
        Instant now = Instant.now();
        if (open.lastActiveAt != null && now.isAfter(open.lastActiveAt))
            open.startedAt = open.startedAt.plus(Duration.between(open.lastActiveAt, now));
        open.lastActiveAt = now;
        attempts.save(open);
        return startView(open, ch, m, true);
    }

    private static Instant lastActive(Attempt a) { return a.lastActiveAt != null ? a.lastActiveAt : a.startedAt; }

    private Map<String, Object> startView(Attempt a, Challenge ch, ConceptMastery m, boolean resumed) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("attemptId", a.id);
        out.put("resumed", resumed);
        out.put("challenge", Map.ofEntries(
                Map.entry("id", ch.id), Map.entry("title", ch.title), Map.entry("descriptionMd", ch.descriptionMd),
                Map.entry("difficulty", ch.difficulty), Map.entry("scaffolding", ch.scaffolding), Map.entry("expectedMinutes", ch.expectedMinutes),
                Map.entry("language", ch.language),
                Map.entry("files", ChallengeService.starterWithMain(ch.language, Json.files(ch.starterFilesJson))),
                Map.entry("testNames", maskedNames(ch)),
                Map.entry("examples", ChallengeService.examples(ch)),
                Map.entry("sampleOutput", challengeService.sampleOutput(ch))));
        if (resumed) {
            if (a.draftFilesJson != null) out.put("draft", Json.files(a.draftFilesJson));
            out.put("hints", Json.list(a.hintsJson));
            Object last = Json.any(a.lastRunJson);
            if (last != null) out.put("lastRun", last);
        }
        out.put("mastery", masteryView(m));
        out.put("practice", a.isPractice());
        out.put("revealed", revealed(a));
        out.put("hintQuota", hintQuota(a.userId).view());
        var guide = guideView(ch, a);
        if (guide != null) out.put("guide", guide);
        out.put("startedAt", a.startedAt.toString());
        out.put("serverNow", Instant.now().toString());
        out.putAll(timeRecord(a.userId, ch.id, a.id));
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
        List<Attempt> open = attempts.findByUserIdAndSubmittedAtIsNullOrderByStartedAtDesc(userId);
        Map<Long, Challenge> byId = ChallengeService.byId(challenges, open.stream().map(a -> a.challengeId).toList());
        for (Attempt a : open) {
            Challenge c = byId.get(a.challengeId);
            if (c != null && c.language.equals(language)) out.add(c.concept);
        }
        return out;
    }

    // ---- run (no mastery update) ----
    public Map<String, Object> run(Long userId, Long attemptId, Map<String, String> files, String modeName) {
        Attempt a = owned(userId, attemptId);
        Challenge ch = challenges.findById(a.challengeId).orElseThrow();
        boolean tests = "tests".equalsIgnoreCase(modeName);
        RunResult r = runner.run(ch.language, files, tests ? Json.files(ch.testsJson) : Map.of(), tests ? CodeRunner.Mode.TESTS : CodeRunner.Mode.MAIN);
        if (tests) { r = ChallengeService.withAllTests(ch, r); a.lastTestsJson = Json.write(r.tests()); }
        a.runs++;
        if (!r.compiled()) a.compileErrors++;
        else if (r.runtimeError() || r.timedOut()) a.runtimeErrors++;
        if (tests && r.compiled() && a.firstRunPassed == null) a.firstRunPassed = r.passed();
        Map<String, Object> view = runView(r, a, ch);
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
        RunResult r = ChallengeService.withAllTests(ch, runner.run(ch.language, files, Json.files(ch.testsJson), CodeRunner.Mode.TESTS));

        int total = Math.max(ChallengeService.testNames(ch).size(), r.total());
        int passed = r.compiled() ? r.passed() : 0;
        if (!r.compiled()) a.compileErrors++;   // a submit is not a run: it doesn't count toward the run total
        if (r.compiled()) a.lastTestsJson = Json.write(r.tests());
        boolean allPassed = total > 0 && passed == total;
        int failedBefore = a.failedSubmissions == null ? 0 : a.failedSubmissions;

        if (!allPassed && !finish) {
            a.failedSubmissions = failedBefore + 1;
            a.draftFilesJson = Json.write(files);
            Map<String, Object> view = runView(r, a, ch);
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
                    r.compiled() ? (total - passed) + " of " + total + " tests failed. Example tests show why; hidden tests can be revealed for a small mastery cost."
                                 : "Your code didn't compile, so no tests could run. Fix the errors shown in the console.",
                    "Fix your code and submit again. Each extra submission lowers the mastery this challenge can earn, but finishing it still counts."));
            return out;
        }

        a.submittedAt = Instant.now();
        a.testsPassed = passed; a.testsTotal = total;
        a.failedSubmissions = failedBefore;
        a.finalFilesJson = Json.write(files);

        long seconds = Math.max(0, Duration.between(a.startedAt, a.submittedAt).toSeconds());
        a.elapsedSeconds = (int) Math.min(Integer.MAX_VALUE, seconds);
        double minutes = seconds / 60.0;
        Double firstRatio = a.firstRunPassed == null || total == 0 ? null : Math.min(1.0, (double) a.firstRunPassed / total);
        var signals = new MasteryService.Signals(passed, total, a.runs, a.compileErrors, a.hintsUsed, minutes, ch.expectedMinutes,
                firstRatio, failedBefore, revealed(a).size());
        var o = mastery.apply(userId, ch.language, ch.concept, ch.difficulty, signals, a.isPractice());
        a.performance = o.performance(); a.scoreAfter = o.scoreAfter();
        attempts.save(a);
        logEvent(a.id, "submit", "{\"passed\":" + passed + ",\"total\":" + total + "}");

        Map<String, Object> out = new LinkedHashMap<>(runView(r, a, ch));
        out.put("final", true);
        out.put("practice", a.isPractice());
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
        out.put("fluency", round(o.fluency()));
        out.put("calibration", o.fluencyLabel());
        if (total > 0 && passed == total) {
            JsonNode g = ChallengeService.guide(ch);
            if (g != null && g.hasNonNull("reveal")) {            // finished a guided challenge: now it can be named
                @SuppressWarnings("unchecked") Map<String, Object> rv = new LinkedHashMap<>(Json.M.convertValue(g.get("reveal"), Map.class));
                rv.put("concept", ch.concept);
                out.put("reveal", rv);
            }
        }
        out.put("feedback", feedback(signals, o, passed, total, a.isPractice()));
        out.put("minutes", round(minutes));
        out.put("seconds", a.elapsedSeconds);
        out.putAll(timeRecord(userId, ch.id, null));
        return out;
    }

    // ---- guided challenges ----
    /** What the browser may see of a guide: the question and options always, the answer and why only once it's done. */
    Map<String, Object> guideView(Challenge ch, Attempt a) {
        JsonNode g = ChallengeService.guide(ch);
        if (g == null) return null;
        boolean done = Boolean.TRUE.equals(a.quizSolved);
        Map<String, Object> quiz = new LinkedHashMap<>();
        quiz.put("question", g.path("quiz").path("question").asText());
        quiz.put("options", Json.M.convertValue(g.path("quiz").path("options"), List.class));
        quiz.put("tries", a.quizTries == null ? 0 : a.quizTries);
        quiz.put("solved", done);
        if (done) {
            quiz.put("answers", Json.M.convertValue(g.path("quiz").path("answers"), List.class));
            quiz.put("explanation", g.path("quiz").path("explanation").asText());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("quiz", quiz);
        out.put("steps", Json.M.convertValue(g.path("steps"), List.class));
        return out;
    }

    /** Answer the diagnose question. It is done on a right answer, or after two tries (then the answer is shown, so nobody is stuck). */
    public Map<String, Object> answerQuiz(Long userId, Long attemptId, String optionId) {
        Attempt a = owned(userId, attemptId);
        Challenge ch = challenges.findById(a.challengeId).orElseThrow();
        JsonNode g = ChallengeService.guide(ch);
        if (g == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This challenge has no diagnose question");
        Set<String> answers = new HashSet<>();
        g.path("quiz").path("answers").forEach(n -> answers.add(n.asText()));
        boolean correct = optionId != null && answers.contains(optionId);
        if (!Boolean.TRUE.equals(a.quizSolved)) {
            a.quizTries = (a.quizTries == null ? 0 : a.quizTries) + 1;
            if (correct || a.quizTries >= 2) a.quizSolved = true;
            a.lastActiveAt = Instant.now();
            attempts.save(a);
            logEvent(a.id, "quiz", "{\"correct\":" + correct + "}");
        }
        @SuppressWarnings("unchecked") Map<String, Object> quiz = new LinkedHashMap<>((Map<String, Object>) guideView(ch, a).get("quiz"));
        quiz.put("correct", correct);
        return quiz;
    }

    // ---- clock ----
    /** Restart the clock on an open attempt (e.g. when revisiting a question to time a fresh try). */
    public Map<String, Object> resetTimer(Long userId, Long attemptId) {
        Attempt a = owned(userId, attemptId);
        if (a.submittedAt != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "This attempt is already submitted");
        a.startedAt = Instant.now();
        a.lastActiveAt = a.startedAt;
        a.timerResets = (a.timerResets == null ? 0 : a.timerResets) + 1;
        attempts.save(a);
        logEvent(a.id, "timer_reset", "{}");
        return Map.of("startedAt", a.startedAt.toString(), "serverNow", Instant.now().toString());
    }

    /**
     * This learner's record on a challenge: which attempt this is, how many there were, the best (fastest) solved
     * time and the longest time spent on a submission so far.
     */
    Map<String, Object> timeRecord(Long userId, Long challengeId, Long currentOpenId) {
        List<Attempt> mine = attempts.findByUserId(userId).stream().filter(x -> challengeId.equals(x.challengeId)).toList();
        Integer best = null, longest = null;
        for (Attempt x : mine) {
            Integer secs = seconds(x);
            if (secs == null) continue;
            if (x.testsTotal > 0 && x.testsPassed == x.testsTotal) best = best == null ? secs : Math.min(best, secs);
            longest = longest == null ? secs : Math.max(longest, secs);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("attemptNumber", mine.size());
        out.put("submittedAttempts", (int) mine.stream().filter(x -> x.submittedAt != null).count());
        out.put("bestSeconds", best);
        out.put("longestSeconds", longest);
        return out;
    }

    /** Time spent on a submitted attempt (older rows predate elapsedSeconds). */
    static Integer seconds(Attempt x) {
        if (x.submittedAt == null) return null;
        if (x.elapsedSeconds != null) return x.elapsedSeconds;
        return (int) Math.max(0, Duration.between(x.startedAt, x.submittedAt).toSeconds());
    }

    // ---- hints ----
    public Map<String, Object> hint(Long userId, Long attemptId, Map<String, String> files, String lastFailures) {
        Attempt a = owned(userId, attemptId);
        if (a.submittedAt != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "This attempt is already submitted");
        HintQuota quota = hintQuota(userId);
        if (quota.left() <= 0) {
            long minutes = Math.max(1, (quota.resetSeconds() + 59) / 60);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "You've used your " + quota.limit() + " hints for this hour. The next one opens in " + minutes + " min.");
        }
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
        Map<String, Object> resp = new LinkedHashMap<>(out);      // the stored copy stays free of the (changing) allowance
        resp.put("quota", hintQuota(userId).view());
        return resp;
    }

    // ---- hint allowance ----
    public record HintQuota(int limit, int used, int left, long resetSeconds) {
        Map<String, Object> view() { return Map.of("limit", limit, "used", used, "left", left, "resetSeconds", resetSeconds); }
    }

    /** Hints used in the last hour, and (when none are left) how long until the oldest one drops out of the hour. */
    static HintQuota quotaFrom(List<Instant> times, int limit, Instant now) {
        Instant since = now.minus(Duration.ofHours(1));
        List<Instant> inWindow = times.stream().filter(t -> t.isAfter(since)).sorted().toList();
        int used = inWindow.size();
        long reset = limit > 0 && used >= limit ? Math.max(1, Duration.between(now, inWindow.get(used - limit).plus(Duration.ofHours(1))).toSeconds()) : 0;
        return new HintQuota(limit, used, Math.max(0, limit - used), reset);
    }

    HintQuota hintQuota(Long userId) {
        Instant now = Instant.now();
        return quotaFrom(events.hintTimesSince(userId, now.minus(Duration.ofHours(1))), hintsPerHour, now);
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
                    case 2 -> "Give a CONCEPTUAL hint: explain the design idea (the OOP concept or SOLID principle) that unlocks the problem in 2-4 sentences. No code.";
                    default -> "Give PARTIAL CODE: a small " + ConceptCatalog.label(ch.language) + " snippet (max 8 lines) for the single most blocking step. Do not provide the whole solution.";
                };
                StringBuilder sb = new StringBuilder("Challenge:\n").append(ch.descriptionMd).append("\n\nLearner's current files:\n");
                if (files != null) files.forEach((k, v) -> sb.append("--- ").append(k).append("\n").append(v, 0, Math.min(v.length(), 2500)).append("\n"));
                if (failures != null && !failures.isBlank()) sb.append("\nLatest failing tests:\n").append(failures, 0, Math.min(failures.length(), 1200));
                return ai.chat(ai.hintModel(),
                        "You are a patient " + ConceptCatalog.label(ch.language) + " tutor giving one hint at a time. Markdown allowed, with ```" + ch.language + " code fences. Be brief and encouraging. " + levelRule,
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

    /** The only event types the browser may log. Others (hint, run, reveal) are logged by the server, and counted from there. */
    private static final Set<String> CLIENT_EVENTS = Set.of("first_keystroke", "churn");

    public void telemetry(Long userId, List<Event> batch) {
        for (Event e : batch) {
            if (!CLIENT_EVENTS.contains(e.type())) continue;
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

    private Map<String, Object> runView(RunResult r, Attempt a, Challenge ch) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("compiled", r.compiled());
        o.put("diagnostics", r.diagnostics());
        o.put("stdout", r.stdout());
        o.put("stderr", r.stderr());
        o.put("tests", maskTests(r.tests(), ch, a));
        o.put("timedOut", r.timedOut());
        o.put("exitCode", r.exitCode());
        o.put("millis", r.millis());
        o.put("runs", a.runs);
        return o;
    }

    private static List<String> feedback(MasteryService.Signals s, MasteryService.Outcome o, int passed, int total, boolean practice) {
        List<String> f = new ArrayList<>();
        boolean full = total > 0 && passed == total;
        if (full) f.add("All " + total + " tests passed" + (s.failedSubmissions() > 0 ? " after " + s.failedSubmissions() + " earlier " + (s.failedSubmissions() == 1 ? "try" : "tries") + "." : "."));
        else f.add(passed + " of " + total + " tests passed. You moved on, so this result is final.");
        if (practice) { f.add("Practice redo: graded for you, mastery unchanged."); return f; }
        if (s.hintLevel() >= 4) f.add("You viewed the full solution, so this attempt earns little mastery. Rebuild it from memory next time.");
        else if (s.hintLevel() > 0) f.add("You used hints up to level " + s.hintLevel() + ". A pass without help builds your streak.");
        if (s.reveals() > 0) f.add("You revealed " + s.reveals() + " hidden " + (s.reveals() == 1 ? "test" : "tests") + ", which costs a little mastery and resets the streak.");
        if (s.failedSubmissions() > 0 && full) f.add("Needing to resubmit lowered the mastery earned and resets the streak.");
        if (full && s.hintLevel() == 0 && s.reveals() == 0 && s.failedSubmissions() == 0) f.add("No help used. Nice.");
        switch (o.fluencyLabel()) {
            case "fluent" -> { if (full) f.add("You worked fluently: most tests passed early, with few runs."); }
            case "effortful" -> f.add("This one took real effort. Running the example tests early helps you see what's expected.");
            default -> {}
        }
        if (s.compileErrors() > 4) f.add(s.compileErrors() + " compile errors: read the first error carefully before running again.");
        if (o.mastered()) f.add("Concept mastered. You passed " + MasteryService.MASTERY_STREAK + " challenges in a row at your level without help.");
        else if (o.cleanPass()) f.add("Clean pass: streak " + o.streak() + " of " + MasteryService.MASTERY_STREAK + ".");
        return f;
    }

    // ---- hidden tests ----
    /** Test names for the UI: the example tests by name, the rest as "Hidden test N". */
    static List<String> maskedNames(Challenge ch) {
        List<String> names = ChallengeService.testNames(ch);
        int vis = ChallengeService.visibleCount(ch, names.size());
        List<String> out = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) out.add(i < vis ? names.get(i) : "Hidden test " + (i - vis + 1));
        return out;
    }

    /** Example tests show name and message; hidden ones only pass/fail, unless the learner revealed them. */
    private static List<Map<String, Object>> maskTests(List<RunResult.TestResult> tests, Challenge ch, Attempt a) {
        int vis = ChallengeService.visibleCount(ch, Math.max(tests.size(), ChallengeService.testNames(ch).size()));
        Set<Integer> shown = revealed(a);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < tests.size(); i++) {
            var t = tests.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            boolean hidden = i >= vis, open = !hidden || shown.contains(i);
            m.put("name", open ? t.name() : "Hidden test " + (i - vis + 1));
            m.put("passed", t.passed());
            m.put("message", open ? t.message() : "");
            m.put("hidden", hidden);
            m.put("revealed", hidden && shown.contains(i));
            m.put("index", i);
            out.add(m);
        }
        return out;
    }

    static Set<Integer> revealed(Attempt a) {
        Set<Integer> out = new TreeSet<>();
        for (Object o : Json.list(a.revealedJson)) if (o instanceof Number n) out.add(n.intValue());
        return out;
    }

    /** Reveal one hidden test (what it checks and its last result). Costs a little mastery on this attempt. */
    public Map<String, Object> reveal(Long userId, Long attemptId, int index) {
        Attempt a = owned(userId, attemptId);
        if (a.submittedAt != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "This attempt is already submitted");
        Challenge ch = challenges.findById(a.challengeId).orElseThrow();
        List<String> names = ChallengeService.testNames(ch);
        int vis = ChallengeService.visibleCount(ch, names.size());
        if (index < vis || index >= names.size()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a hidden test");
        Set<Integer> shown = revealed(a);
        shown.add(index);
        a.revealedJson = Json.write(new ArrayList<>(shown));
        a.lastActiveAt = Instant.now();
        attempts.save(a);
        logEvent(a.id, "reveal", "{\"index\":" + index + "}");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("index", index);
        out.put("name", names.get(index));
        List<Object> last = Json.list(a.lastTestsJson);
        if (index < last.size() && last.get(index) instanceof Map<?, ?> t) { out.put("passed", t.get("passed")); out.put("message", t.get("message")); }
        out.put("reveals", shown.size());
        return out;
    }

    // ---- history ----
    public record HistoryFilter(String language, String concept, String track, String result, Integer days, Long challengeId) {}

    /** Submitted attempts, newest first, filtered (language, topic, track, result, last N days, one challenge), paginated. */
    public Map<String, Object> historyPage(Long userId, int page, int size, HistoryFilter f) {
        List<Map<String, Object>> all = historyRows(userId, f);
        int sz = Math.max(1, Math.min(50, size)), pg = Math.max(0, page);
        int from = Math.min(all.size(), pg * sz), to = Math.min(all.size(), from + sz);
        int pages = (all.size() + sz - 1) / sz;
        return Map.of("items", all.subList(from, to), "page", pg, "size", sz, "total", all.size(), "pages", pages);
    }

    private List<Map<String, Object>> historyRows(Long userId, HistoryFilter f) {
        List<Attempt> mine = attempts.findByUserIdAndSubmittedAtIsNotNullOrderBySubmittedAtDesc(userId);
        Map<Long, Challenge> chs = ChallengeService.byId(challenges, mine.stream().map(a -> a.challengeId).toList());
        // attempt numbers count every submission of a challenge in time order
        Map<Long, Integer> seen = new HashMap<>();
        Map<Long, Integer> number = new HashMap<>();
        List<Attempt> oldestFirst = new ArrayList<>(mine);
        Collections.reverse(oldestFirst);
        for (Attempt a : oldestFirst) number.put(a.id, seen.merge(a.challengeId, 1, Integer::sum));
        Instant since = f != null && f.days() != null && f.days() > 0 ? Instant.now().minus(Duration.ofDays(f.days())) : null;

        List<Map<String, Object>> out = new ArrayList<>();
        for (Attempt a : mine) {
            Challenge ch = chs.get(a.challengeId);
            if (ch == null) continue;
            var info = catalog.find(ch.language, ch.concept);
            boolean passed = a.testsTotal > 0 && a.testsPassed == a.testsTotal;
            if (f != null) {
                if (f.language() != null && !f.language().isBlank() && !f.language().equals(ch.language)) continue;
                if (f.concept() != null && !f.concept().isBlank() && !f.concept().equals(ch.concept)) continue;
                if (f.track() != null && !f.track().isBlank() && !info.map(c -> c.track().equals(f.track())).orElse(false)) continue;
                if ("passed".equals(f.result()) && !passed) continue;
                if ("failed".equals(f.result()) && passed) continue;
                if ("practice".equals(f.result()) && !a.isPractice()) continue;
                if (since != null && a.submittedAt.isBefore(since)) continue;
                if (f.challengeId() != null && !f.challengeId().equals(ch.id)) continue;
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("attemptId", a.id); r.put("challengeId", ch.id); r.put("title", ch.title);
            r.put("concept", ch.concept);
            r.put("conceptTitle", info.map(ConceptCatalog.Concept::title).orElse(ch.concept));
            r.put("track", info.map(ConceptCatalog.Concept::track).orElse(""));
            r.put("language", ch.language); r.put("difficulty", ch.difficulty);
            r.put("passed", a.testsPassed); r.put("total", a.testsTotal);
            r.put("delta", round(a.scoreAfter - a.scoreBefore)); r.put("hints", a.hintsUsed);
            r.put("practice", a.isPractice());
            r.put("seconds", seconds(a));
            r.put("attemptNumber", number.getOrDefault(a.id, 1));
            r.put("at", a.submittedAt.toString());
            out.add(r);
        }
        return out;
    }

    /** History grouped by challenge: every attempt, best time and best result per challenge. */
    public List<Map<String, Object>> historyByChallenge(Long userId, HistoryFilter f) {
        Map<Long, Map<String, Object>> groups = new LinkedHashMap<>();
        for (Map<String, Object> r : historyRows(userId, f)) {
            Map<String, Object> g = groups.computeIfAbsent((Long) r.get("challengeId"), id -> {
                Map<String, Object> n = new LinkedHashMap<>();
                for (String k : List.of("challengeId", "title", "concept", "conceptTitle", "track", "language", "difficulty")) n.put(k, r.get(k));
                n.put("attempts", new ArrayList<Map<String, Object>>());
                n.put("solved", 0); n.put("bestSeconds", null); n.put("lastAt", r.get("at"));
                return n;
            });
            @SuppressWarnings("unchecked") var list = (List<Map<String, Object>>) g.get("attempts");
            list.add(r);
            boolean ok = (int) r.get("total") > 0 && r.get("passed").equals(r.get("total"));
            if (ok) {
                g.put("solved", (int) g.get("solved") + 1);
                Integer secs = (Integer) r.get("seconds"), best = (Integer) g.get("bestSeconds");
                if (secs != null && (best == null || secs < best)) g.put("bestSeconds", secs);
            }
        }
        return new ArrayList<>(groups.values());
    }

    /** Totals and a per-day series for the profile charts, plus breakdowns by topic and language. */
    public Map<String, Object> historySummary(Long userId, int days, int tzOffsetMinutes) {
        int minutesTz = Math.max(-14 * 60, Math.min(14 * 60, tzOffsetMinutes));
        java.time.ZoneOffset zone = java.time.ZoneOffset.ofTotalSeconds(-minutesTz * 60);
        java.time.LocalDate today = java.time.LocalDate.now(zone);
        int n = Math.max(7, Math.min(365, days));
        Map<java.time.LocalDate, int[]> perDay = new TreeMap<>(); // [seconds, solved, submitted]
        for (int i = n - 1; i >= 0; i--) perDay.put(today.minusDays(i), new int[3]);
        Map<String, Map<String, Object>> byConcept = new LinkedHashMap<>();
        Map<String, int[]> byLanguage = new LinkedHashMap<>();
        int attemptsCount = 0, solved = 0; long totalSeconds = 0; Set<Long> challengesSeen = new HashSet<>();
        for (Map<String, Object> r : historyRows(userId, null)) {
            attemptsCount++;
            boolean ok = (int) r.get("total") > 0 && r.get("passed").equals(r.get("total"));
            if (ok) solved++;
            Integer secs = (Integer) r.get("seconds");
            if (secs != null) totalSeconds += secs;
            challengesSeen.add((Long) r.get("challengeId"));
            var day = java.time.LocalDate.ofInstant(Instant.parse((String) r.get("at")), zone);
            int[] d = perDay.get(day);
            if (d != null) { d[0] += secs == null ? 0 : secs; d[1] += ok ? 1 : 0; d[2]++; }
            var c = byConcept.computeIfAbsent((String) r.get("concept"), k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("concept", k); m.put("conceptTitle", r.get("conceptTitle")); m.put("track", r.get("track"));
                m.put("attempts", 0); m.put("solved", 0); m.put("seconds", 0L);
                return m;
            });
            c.put("attempts", (int) c.get("attempts") + 1);
            if (ok) c.put("solved", (int) c.get("solved") + 1);
            c.put("seconds", (long) c.get("seconds") + (secs == null ? 0 : secs));
            int[] l = byLanguage.computeIfAbsent((String) r.get("language"), k -> new int[2]);
            l[0]++; if (ok) l[1]++;
        }
        List<Map<String, Object>> series = new ArrayList<>();
        perDay.forEach((d, v) -> series.add(Map.of("date", d.toString(), "minutes", round(v[0] / 60.0), "solved", v[1], "submitted", v[2])));
        List<Map<String, Object>> langs = new ArrayList<>();
        byLanguage.forEach((k, v) -> langs.add(Map.of("language", k, "attempts", v[0], "solved", v[1])));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", series);
        out.put("byConcept", new ArrayList<>(byConcept.values()));
        out.put("byLanguage", langs);
        out.put("totals", Map.of("attempts", attemptsCount, "solved", solved, "challenges", challengesSeen.size(),
                "minutes", round(totalSeconds / 60.0), "avgMinutes", attemptsCount == 0 ? 0 : round(totalSeconds / 60.0 / attemptsCount)));
        return out;
    }

    /** One submitted attempt: the challenge and the code that was submitted. */
    public Map<String, Object> attemptDetail(Long userId, Long attemptId) {
        Attempt a = owned(userId, attemptId);
        Challenge ch = challenges.findById(a.challengeId).orElseThrow();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("attemptId", a.id);
        out.put("challenge", Map.of("id", ch.id, "title", ch.title, "descriptionMd", ch.descriptionMd, "language", ch.language,
                "concept", ch.concept, "difficulty", ch.difficulty));
        out.put("files", Json.files(a.finalFilesJson != null ? a.finalFilesJson : a.draftFilesJson));
        out.put("passed", a.testsPassed); out.put("total", a.testsTotal);
        out.put("delta", round(a.scoreAfter - a.scoreBefore));
        out.put("practice", a.isPractice());
        out.put("submittedAt", a.submittedAt == null ? null : a.submittedAt.toString());
        return out;
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

    public static double round(double d) { return Math.round(d * 1000.0) / 1000.0; }
}
