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
        for (Attempt a : mine) {
            bump(perDay, a.startedAt, zone);
            runs += a.runs; hints += a.hintsUsed;
            if (a.submittedAt != null) {
                bump(perDay, a.submittedAt, zone);
                submissions++;
                if (a.testsTotal > 0 && a.testsPassed == a.testsTotal) solved++;
                minutesCoding += Math.max(0, Duration.between(a.startedAt, a.submittedAt).toSeconds() / 60.0);
            }
        }
        List<Long> ids = mine.stream().map(a -> a.id).toList();
        if (!ids.isEmpty()) for (EventLog e : events.findByAttemptIdInAndTypeIn(ids, List.of("run", "hint"))) bump(perDay, e.ts, zone);

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

        var rows = mastery.findByUserIdAndLanguage(userId, language);
        int masteredCount = (int) rows.stream().filter(m -> m.mastered).count();
        // concepts that can be practised (and so mastered) in this language; other tracks are lessons for now
        int conceptCount = (int) catalog.list(language).stream().filter(c -> catalog.practiceOpen(language, c.id())).count();
        // unstarted concepts count as 0 progress
        double avgScore = conceptCount == 0 ? 0 : rows.stream().filter(m -> m.attemptsCount > 0).mapToDouble(m -> m.score).sum() / conceptCount;

        List<Map<String, Object>> recent = new ArrayList<>();
        mine.stream().filter(a -> a.submittedAt != null).sorted(Comparator.comparing((Attempt a) -> a.submittedAt).reversed()).limit(8).forEach(a -> {
            Challenge ch = challenges.findById(a.challengeId).orElse(null);
            if (ch == null) return;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("title", ch.title);
            r.put("concept", ch.concept);
            r.put("conceptTitle", catalog.find(ch.language, ch.concept).map(ConceptCatalog.Concept::title).orElse(ch.concept));
            r.put("passed", a.testsPassed); r.put("total", a.testsTotal);
            r.put("delta", AttemptService.round(a.scoreAfter - a.scoreBefore));
            r.put("at", a.submittedAt.toString());
            recent.add(r);
        });

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
