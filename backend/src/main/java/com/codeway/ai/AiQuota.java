package com.codeway.ai;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

/**
 * Daily caps on AI calls (UTC days): one per user and one for the whole app, so open sign-up can't run up the
 * AI bill. Every model request counts, including generation retries. Over a cap the call is refused and callers
 * fall back (stored hints, the challenge bank, built-in lessons). Counters are in memory, so a restart resets them.
 */
@Component
public class AiQuota {
    private final int perUser;
    private final int global;
    private final Clock clock;
    private LocalDate day;
    private int globalUsed;
    private final Map<Long, Integer> used = new HashMap<>();

    @Autowired
    public AiQuota(@Value("${codeway.ai.daily-user-limit:40}") int perUser,
                   @Value("${codeway.ai.daily-global-limit:300}") int global) {
        this(perUser, global, Clock.systemUTC());
    }

    AiQuota(int perUser, int global, Clock clock) {
        this.perUser = perUser; this.global = global; this.clock = clock;
        this.day = LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    /** Reserves one AI call for this user (null = system work, counted only against the global cap). */
    public synchronized boolean tryAcquire(Long userId) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (!today.equals(day)) { day = today; globalUsed = 0; used.clear(); }
        if (global > 0 && globalUsed >= global) return false;
        if (userId != null && perUser > 0 && used.getOrDefault(userId, 0) >= perUser) return false;
        globalUsed++;
        if (userId != null) used.merge(userId, 1, Integer::sum);
        return true;
    }

    public synchronized Map<String, Integer> snapshot(Long userId) {
        return Map.of("userUsed", userId == null ? 0 : used.getOrDefault(userId, 0), "userLimit", perUser,
                "globalUsed", globalUsed, "globalLimit", global);
    }
}
