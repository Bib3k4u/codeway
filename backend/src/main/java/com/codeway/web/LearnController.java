package com.codeway.web;

import com.codeway.auth.AuthInterceptor;
import com.codeway.domain.*;
import com.codeway.repo.MasteryRepo;
import com.codeway.service.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api")
public class LearnController {
    public record NextReq(String language, String concept) {}
    public record RunReq(Long attemptId, Map<String, String> files, String mode) {}
    public record SubmitReq(Map<String, String> files, Integer confidence, Boolean finish) {}
    public record HintReq(Map<String, String> files, String failures) {}
    public record DraftReq(Map<String, String> files) {}
    public record RevealReq(Integer index) {}
    public record QuizReq(String option) {}
    public record TelemetryReq(List<AttemptService.Event> events) {}

    private final ConceptCatalog catalog;
    private final MasteryRepo masteryRepo;
    private final MasteryService mastery;
    private final LessonService lessons;
    private final ChallengeService challenges;
    private final AttemptService attempts;
    private final StatsService stats;

    public LearnController(ConceptCatalog catalog, MasteryRepo masteryRepo, MasteryService mastery, LessonService lessons,
                           ChallengeService challenges, AttemptService attempts, StatsService stats) {
        this.catalog = catalog; this.masteryRepo = masteryRepo; this.mastery = mastery;
        this.lessons = lessons; this.challenges = challenges; this.attempts = attempts; this.stats = stats;
    }

    private static Long uid(HttpServletRequest r) { return (Long) r.getAttribute(AuthInterceptor.ATTR); }

    @GetMapping("/stats")
    public Map<String, Object> stats(HttpServletRequest req, @RequestParam(defaultValue = "java") String language, @RequestParam(defaultValue = "0") int tz) {
        return stats.stats(uid(req), language, tz);
    }

    @GetMapping("/mastery")
    public List<Map<String, Object>> mastery(HttpServletRequest req, @RequestParam(defaultValue = "java") String language) {
        Map<String, ConceptMastery> byConcept = new HashMap<>();
        masteryRepo.findByUserIdAndLanguage(uid(req), language).forEach(m -> byConcept.put(m.concept, m));
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> open = attempts.openConcepts(uid(req), language);
        for (var c : catalog.list(language)) {
            // All of this learner's rows were loaded above in one query; a concept without a row is simply not started yet.
            // (getOrDefault(.., mastery.get(..)) would run a database query for every concept, found or not.)
            ConceptMastery m = byConcept.get(c.id());
            if (m == null) m = mastery.blank(uid(req), language, c.id());
            Map<String, Object> row = new LinkedHashMap<>();
            boolean practice = catalog.practiceOpen(language, c.id());
            row.put("id", c.id()); row.put("title", c.title()); row.put("summary", c.summary()); row.put("symbol", c.symbol());
            row.put("track", c.track()); if (c.group() != null) row.put("group", c.group());
            row.put("available", practice && challenges.hasContent(language, c.id()));
            row.put("score", AttemptService.round(m.score)); row.put("streak", m.streak);
            row.put("progress", m.attemptsCount > 0 ? AttemptService.round(m.score) : 0.0);
            row.put("practice", practice);
            row.put("mastered", m.mastered); row.put("attempts", m.attemptsCount);
            row.put("inProgress", open.contains(c.id()));
            out.add(row);
        }
        return out;
    }

    /** The learning path, in order. */
    @GetMapping("/tracks")
    public List<ConceptCatalog.Track> tracks() { return catalog.tracks(); }

    @GetMapping("/lessons")
    public Map<String, Object> lesson(@RequestParam(defaultValue = "java") String language, @RequestParam String concept) {
        Lesson l = lessons.get(language, concept);
        var c = catalog.find(language, concept).orElseThrow();
        return Map.of("language", language, "concept", concept, "title", c.title(), "contentMd", l.contentMd, "source", l.source,
                "track", c.track(), "practice", catalog.practiceOpen(language, concept));
    }

    @PostMapping("/challenges/next")
    public Map<String, Object> next(HttpServletRequest req, @RequestBody NextReq b) {
        return attempts.start(uid(req), b.language() == null ? "java" : b.language(), b.concept());
    }

    @PostMapping("/run")
    public Map<String, Object> run(HttpServletRequest req, @RequestBody RunReq b) {
        return attempts.run(uid(req), b.attemptId(), b.files(), b.mode());
    }

    @PostMapping("/attempts/{id}/submit")
    public Map<String, Object> submit(HttpServletRequest req, @PathVariable Long id, @RequestBody SubmitReq b) {
        return attempts.submit(uid(req), id, b.files(), b.confidence(), Boolean.TRUE.equals(b.finish()));
    }

    @PostMapping("/attempts/{id}/hint")
    public Map<String, Object> hint(HttpServletRequest req, @PathVariable Long id, @RequestBody HintReq b) {
        return attempts.hint(uid(req), id, b.files(), b.failures());
    }

    /** Answer the diagnose question of a guided challenge. */
    @PostMapping("/attempts/{id}/quiz")
    public Map<String, Object> quiz(HttpServletRequest req, @PathVariable Long id, @RequestBody QuizReq b) {
        return attempts.answerQuiz(uid(req), id, b.option());
    }

    /** Reveal one hidden test of an open attempt (costs a little mastery). */
    @PostMapping("/attempts/{id}/reveal")
    public Map<String, Object> reveal(HttpServletRequest req, @PathVariable Long id, @RequestBody RevealReq b) {
        return attempts.reveal(uid(req), id, b.index() == null ? -1 : b.index());
    }

    /** Submission history, newest first, paginated, with optional filters. */
    @GetMapping("/history")
    public Map<String, Object> historyPage(HttpServletRequest req, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
                                           @RequestParam(required = false) String language, @RequestParam(required = false) String concept,
                                           @RequestParam(required = false) String track, @RequestParam(required = false) String result,
                                           @RequestParam(required = false) Integer days, @RequestParam(required = false) Long challenge) {
        return attempts.historyPage(uid(req), page, size, new AttemptService.HistoryFilter(language, concept, track, result, days, challenge));
    }

    /** History grouped by challenge (every attempt, best time). */
    @GetMapping("/history/challenges")
    public List<Map<String, Object>> historyByChallenge(HttpServletRequest req, @RequestParam(required = false) String language,
                                                       @RequestParam(required = false) String concept, @RequestParam(required = false) String track,
                                                       @RequestParam(required = false) String result, @RequestParam(required = false) Integer days) {
        return attempts.historyByChallenge(uid(req), new AttemptService.HistoryFilter(language, concept, track, result, days, null));
    }

    /** Per-day series and breakdowns for the profile charts. */
    @GetMapping("/history/summary")
    public Map<String, Object> historySummary(HttpServletRequest req, @RequestParam(defaultValue = "90") int days, @RequestParam(defaultValue = "0") int tz) {
        return attempts.historySummary(uid(req), days, tz);
    }

    /** Restart the clock on an open attempt. */
    @PostMapping("/attempts/{id}/timer/reset")
    public Map<String, Object> resetTimer(HttpServletRequest req, @PathVariable Long id) {
        return attempts.resetTimer(uid(req), id);
    }

    /** One past submission with its code. */
    @GetMapping("/attempts/{id}")
    public Map<String, Object> attempt(HttpServletRequest req, @PathVariable Long id) {
        return attempts.attemptDetail(uid(req), id);
    }

    /** Redo a past challenge as practice (graded, mastery unchanged). */
    @PostMapping("/challenges/{id}/redo")
    public Map<String, Object> redo(HttpServletRequest req, @PathVariable Long id) {
        return attempts.redo(uid(req), id);
    }

    @PutMapping("/attempts/{id}/draft")
    public Map<String, Object> draft(HttpServletRequest req, @PathVariable Long id, @RequestBody DraftReq b) {
        attempts.saveDraft(uid(req), id, b.files());
        return Map.of("ok", true);
    }

    @GetMapping("/attempts/history")
    public List<Map<String, Object>> history(HttpServletRequest req, @RequestParam(defaultValue = "java") String language, @RequestParam String concept) {
        return attempts.history(uid(req), language, concept);
    }

    @PostMapping("/telemetry")
    public Map<String, Object> telemetry(HttpServletRequest req, @RequestBody TelemetryReq b) {
        attempts.telemetry(uid(req), b.events() == null ? List.of() : b.events());
        return Map.of("ok", true);
    }
}
