package com.codeway.web;

import com.codeway.auth.*;
import com.codeway.domain.User;
import com.codeway.repo.UserRepo;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AuthController {
    public record Creds(@Email @NotBlank String email, @NotBlank @Size(min = 6) String password, String displayName) {}

    private final UserRepo users;
    private final BCryptPasswordEncoder enc;
    private final TokenService tokens;

    public AuthController(UserRepo users, BCryptPasswordEncoder enc, TokenService tokens) {
        this.users = users; this.enc = enc; this.tokens = tokens;
    }

    @GetMapping("/health")
    public Map<String, Object> health() { return Map.of("ok", true); }

    @PostMapping("/auth/register")
    public Map<String, Object> register(@RequestBody @jakarta.validation.Valid Creds c) {
        String email = c.email().trim().toLowerCase();
        if (users.findByEmail(email).isPresent()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
        User u = new User();
        u.email = email;
        u.passwordHash = enc.encode(c.password());
        u.displayName = c.displayName() == null || c.displayName().isBlank() ? email.split("@")[0] : c.displayName().trim();
        users.save(u);
        return session(u);
    }

    @PostMapping("/auth/login")
    public Map<String, Object> login(@RequestBody @jakarta.validation.Valid Creds c) {
        User u = users.findByEmail(c.email().trim().toLowerCase())
                .filter(x -> enc.matches(c.password(), x.passwordHash))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password"));
        return session(u);
    }

    @GetMapping("/auth/me")
    public Map<String, Object> me(HttpServletRequest req) {
        Long id = (Long) req.getAttribute(AuthInterceptor.ATTR);
        User u = users.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        return Map.of("id", u.id, "email", u.email, "displayName", u.displayName, "preferredLanguage", preferred(u));
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

    private Map<String, Object> session(User u) {
        return Map.of("token", tokens.issue(u.id), "user", Map.of("id", u.id, "email", u.email, "displayName", u.displayName, "preferredLanguage", preferred(u)));
    }
}
