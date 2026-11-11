package com.codeway.config;

import com.codeway.auth.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-user (or per-IP before login) sliding-window limits on the expensive endpoints:
 * code runs (CPU) and AI calls (cost). In-memory, so it is per instance, which suits a single test server.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {
    private record Rule(String name, int max, long windowMs) {}

    @Value("${codeway.ratelimit.enabled:true}") private boolean enabled;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    private static Rule ruleFor(String method, String path) {
        if (!"POST".equals(method) && !path.startsWith("/api/lessons")) return null;
        if (path.equals("/api/run")) return new Rule("run", 30, 60_000);
        if (path.endsWith("/submit")) return new Rule("submit", 12, 60_000);
        if (path.endsWith("/hint")) return new Rule("hint", 10, 60_000);
        if (path.equals("/api/challenges/next")) return new Rule("next", 8, 60_000);  // may call the AI
        if (path.startsWith("/api/lessons")) return new Rule("lesson", 20, 60_000);    // may call the AI
        return null;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        if (!enabled || "OPTIONS".equals(req.getMethod())) return true;
        Rule rule = ruleFor(req.getMethod(), req.getRequestURI());
        if (rule == null) return true;
        Object uid = req.getAttribute(AuthInterceptor.ATTR);
        String who = uid != null ? "u" + uid : "ip" + req.getRemoteAddr();
        String key = rule.name() + ":" + who;
        long now = System.currentTimeMillis();
        Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > rule.windowMs()) q.pollFirst();
            if (q.size() >= rule.max()) {
                long retry = Math.max(1, (rule.windowMs() - (now - q.peekFirst())) / 1000);
                res.setStatus(429);
                res.setHeader("Retry-After", String.valueOf(retry));
                res.setContentType("application/json");
                res.getWriter().write("{\"error\":\"You're going a bit fast. Please wait " + retry + "s and try again.\"}");
                return false;
            }
            q.addLast(now);
        }
        if (hits.size() > 50_000) hits.entrySet().removeIf(e -> e.getValue().isEmpty());
        return true;
    }
}
