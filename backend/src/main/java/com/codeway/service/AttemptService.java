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
        var info = catalog.find(language, concept).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown concept"));
        if (!catalog.practiceOpen(language, concept)) {
            String track = catalog.track(info.track()).map(ConceptCatalog.Track::title).orElse("This track");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Practice for " + track + " is coming soon. Read the lesson for now.");
        }
        ConceptMastery m = mastery.get(userId, language, concept);
        List<Attempt> open = attempts.findByUserIdAndSubmittedAtIsNullOrderByStartedAtDesc(userId);
        Map<Long, Challenge> chOf = new HashMap<>();
        for (Attempt o : open) challenges.findById(o.challengeId).ifPresent(c -> chOf.put(o.id, c));

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
            if (same != null) return startView(newAttempt(userId, same, m, current.isPractice()), same, m, false);
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
            out.put("startedAt", a.startedAt.toString());
        }
        out.put("mastery", masteryView(m));
        out.put("practice", a.isPractice());
        out.put("revealed", revealed(a));
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

        double minutes = Duration.between(a.startedAt, a.submittedAt).toSeconds() / 60.0;
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
        out.put("feedback", feedback(signals, o, passed, total, a.isPractice()));
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
        int vis = ChallengeService.visibleCount(names.size());
        List<String> out = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) out.add(i < vis ? names.get(i) : "Hidden test " + (i - vis + 1));
        return out;
    }

    /** Example tests show name and message; hidden ones only pass/fail, unless the learner revealed them. */
    private static List<Map<String, Object>> maskTests(List<RunResult.TestResult> tests, Challenge ch, Attempt a) {
        int vis = ChallengeService.visibleCount(Math.max(tests.size(), ChallengeService.testNames(ch).size()));
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
        int vis = ChallengeService.visibleCount(names.size());
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
    /** All submitted attempts, newest first, 20 per page by default. */
    public Map<String, Object> historyPage(Long userId, int page, int size) {
        var p = attempts.findByUserIdAndSubmittedAtIsNotNull(userId,
                org.springframework.data.domain.PageRequest.of(Math.max(0, page), Math.max(1, Math.min(50, size)),
                        org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "submittedAt")));
        List<Map<String, Object>> items = new ArrayList<>();
        for (Attempt a : p.getContent()) {
            Challenge ch = challenges.findById(a.challengeId).orElse(null);
            if (ch == null) continue;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("attemptId", a.id); r.put("challengeId", ch.id); r.put("title", ch.title);
            r.put("concept", ch.concept);
            r.put("conceptTitle", catalog.find(ch.language, ch.concept).map(ConceptCatalog.Concept::title).orElse(ch.concept));
            r.put("language", ch.language); r.put("difficulty", ch.difficulty);
            r.put("passed", a.testsPassed); r.put("total", a.testsTotal);
            r.put("delta", round(a.scoreAfter - a.scoreBefore)); r.put("hints", a.hintsUsed);
            r.put("practice", a.isPractice());
            r.put("at", a.submittedAt.toString());
            items.add(r);
        }
        return Map.of("items", items, "page", p.getNumber(), "size", p.getSize(), "total", p.getTotalElements(), "pages", p.getTotalPages());
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
