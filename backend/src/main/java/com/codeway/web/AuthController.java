package com.codeway.web;

import com.codeway.auth.AuthInterceptor;
import com.codeway.domain.User;
import com.codeway.repo.UserRepo;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AuthController {
    private final UserRepo users;

    public AuthController(UserRepo users) { this.users = users; }

    @GetMapping("/health")
    public Map<String, Object> health() { return Map.of("ok", true); }

    @GetMapping("/auth/me")
    public Map<String, Object> me(HttpServletRequest req) {
        Long id = (Long) req.getAttribute(AuthInterceptor.ATTR);
        User u = users.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        var m = new java.util.HashMap<String, Object>(Map.of("id", u.id, "email", u.email, "displayName", u.displayName, "preferredLanguage", preferred(u)));
        if (u.avatarUrl != null) m.put("avatarUrl", u.avatarUrl);
        return m;
    }

    public record VisitReq(String language) {}

    /** Counts lesson/editor visits per language; the most visited one is preselected next time. */
    @PostMapping("/prefs/visit")
    public Map<String, Object> visit(HttpServletRequest req, @RequestBody VisitReq b) {
        Long id = (Long) req.getAttribute(AuthInterceptor.ATTR);
        User u = users.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        if (b.language() != null && com.codeway.service.ConceptCatalog.LANGUAGES.contains(b.language())) {
            Map<String, Integer> counts = visits(u);
            counts.merge(b.language(), 1, Integer::sum);
            u.languageVisitsJson = com.codeway.service.Json.write(counts);
            users.save(u);
        }
        return Map.of("preferredLanguage", preferred(u));
    }

    private static Map<String, Integer> visits(User u) {
        try {
            if (u.languageVisitsJson == null) return new java.util.HashMap<>();
            return com.codeway.service.Json.M.readValue(u.languageVisitsJson, new com.fasterxml.jackson.core.type.TypeReference<java.util.HashMap<String, Integer>>() {});
        } catch (Exception e) { return new java.util.HashMap<>(); }
    }

    private static String preferred(User u) {
        return visits(u).entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("java");
    }
}
