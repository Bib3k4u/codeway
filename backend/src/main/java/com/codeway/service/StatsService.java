package com.codeway.service;

import com.codeway.domain.*;
import com.codeway.repo.*;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** Activity calendar, streaks and totals for the dashboard and top bar. */
@Service
public class StatsService {
    private static final int WINDOW_DAYS = 371; // 53 weeks

    private final AttemptRepo attempts;
    private final EventRepo events;
    private final ChallengeRepo challenges;
    private final MasteryRepo mastery;
    private final UserRepo users;
    private final ConceptCatalog catalog;

    public StatsService(AttemptRepo attempts, EventRepo events, ChallengeRepo challenges, MasteryRepo mastery, UserRepo users, ConceptCatalog catalog) {
        this.attempts = attempts; this.events = events; this.challenges = challenges; this.mastery = mastery; this.users = users; this.catalog = catalog;
    }

    /** @param tzOffsetMinutes the browser's Date.getTimezoneOffset() (minutes behind UTC, e.g. -330 for IST) */
    public Map<String, Object> stats(Long userId, String language, int tzOffsetMinutes) {
        int minutes = Math.max(-14 * 60, Math.min(14 * 60, tzOffsetMinutes));
        ZoneOffset zone = ZoneOffset.ofTotalSeconds(-minutes * 60);
        LocalDate today = LocalDate.now(zone);

        List<Attempt> mine = attempts.findByUserId(userId);
        Map<LocalDate, Integer> perDay = new TreeMap<>();
        int solved = 0, submissions = 0, runs = 0, hints = 0;
        double minutesCoding = 0;
        // The calendar and streaks count correct submissions (every test passed) per day.
        for (Attempt a : mine) {
            runs += a.runs; hints += a.hintsUsed;
            if (a.submittedAt != null) {
                submissions++;
                if (a.testsTotal > 0 && a.testsPassed == a.testsTotal) { solved++; bump(perDay, a.submittedAt, zone); }
                minutesCoding += Math.max(0, Duration.between(a.startedAt, a.submittedAt).toSeconds() / 60.0);
            }
        }

        // streaks
        int current = 0;
        LocalDate d = perDay.getOrDefault(today, 0) > 0 ? today : today.minusDays(1);
        while (perDay.getOrDefault(d, 0) > 0) { current++; d = d.minusDays(1); }
        int longest = 0, run = 0; LocalDate prev = null;
        for (var e : perDay.entrySet()) {
            if (e.getValue() <= 0) continue;
            run = prev != null && prev.plusDays(1).equals(e.getKey()) ? run + 1 : 1;
            longest = Math.max(longest, run);
            prev = e.getKey();
        }

        LocalDate from = today.minusDays(WINDOW_DAYS - 1);
        Map<String, Integer> days = new LinkedHashMap<>();
        int activeDays = 0, yearTotal = 0;
        for (var e : perDay.entrySet()) {
            if (e.getKey().isBefore(from) || e.getKey().isAfter(today)) continue;
            days.put(e.getKey().toString(), e.getValue());
            activeDays++; yearTotal += e.getValue();
        }

        // Mastery counts a concept once, in whichever language it was mastered; the language picker does not change these totals.
        var rows = mastery.findByUserId(userId);
        Set<String> practicable = new HashSet<>();
        for (String lang : ConceptCatalog.LANGUAGES)
            for (var c : catalog.list(lang)) if (catalog.practiceOpen(lang, c.id())) practicable.add(c.id());
        int masteredCount = (int) rows.stream().filter(m -> m.mastered && practicable.contains(m.concept)).map(m -> m.concept).distinct().count();
        int conceptCount = practicable.size();
        // unstarted concepts count as 0 progress; a concept's progress is its best score in any language
        Map<String, Double> best = new HashMap<>();
        for (var m : rows) if (m.attemptsCount > 0 && practicable.contains(m.concept)) best.merge(m.concept, m.score, Math::max);
        double avgScore = conceptCount == 0 ? 0 : best.values().stream().mapToDouble(Double::doubleValue).sum() / conceptCount;

        List<Map<String, Object>> recent = new ArrayList<>();
        List<Attempt> latest = mine.stream().filter(a -> a.submittedAt != null).sorted(Comparator.comparing((Attempt a) -> a.submittedAt).reversed()).limit(8).toList();
        Map<Long, Challenge> latestChallenges = ChallengeService.byId(challenges, latest.stream().map(a -> a.challengeId).toList());
        for (Attempt a : latest) {
            Challenge ch = latestChallenges.get(a.challengeId);
            if (ch == null) continue;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("title", ch.title);
            r.put("concept", ch.concept);
            r.put("conceptTitle", catalog.find(ch.language, ch.concept).map(ConceptCatalog.Concept::title).orElse(ch.concept));
            r.put("passed", a.testsPassed); r.put("total", a.testsTotal);
            r.put("delta", AttemptService.round(a.scoreAfter - a.scoreBefore));
            r.put("at", a.submittedAt.toString());
            recent.add(r);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("today", today.toString());
        out.put("days", days);
        out.put("currentStreak", current);
        out.put("longestStreak", longest);
        out.put("activeToday", perDay.getOrDefault(today, 0));
        out.put("activeDays", activeDays);
        out.put("yearTotal", yearTotal);
        out.put("solved", solved);
        out.put("submissions", submissions);
        out.put("runs", runs);
        out.put("hints", hints);
        out.put("minutesCoding", Math.round(minutesCoding));
        out.put("mastered", masteredCount);
        out.put("conceptCount", conceptCount);
        out.put("avgScore", AttemptService.round(avgScore));
        out.put("joinedAt", users.findById(userId).map(u -> u.createdAt.toString()).orElse(null));
        out.put("recent", recent);
        return out;
    }

    private static void bump(Map<LocalDate, Integer> m, Instant t, ZoneOffset zone) {
        if (t != null) m.merge(LocalDate.ofInstant(t, zone), 1, Integer::sum);
    }
}
