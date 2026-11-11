package com.codeway.auth;

import com.codeway.domain.User;
import com.codeway.repo.UserRepo;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps a verified Supabase identity to a Codeway user: by Supabase user id, else by email (so accounts from the old
 * email/password sign-in keep their progress), else a new user. Cached so normal requests don't touch the database.
 */
@Service
public class AccountService {
    static final String NO_PASSWORD = "!oauth";
    private final UserRepo users;
    private final Map<String, Long> bySubject = new ConcurrentHashMap<>();

    public AccountService(UserRepo users) { this.users = users; }

    public long userId(SupabaseVerifier.Claims c) {
        Long cached = bySubject.get(c.subject());
        if (cached != null) return cached;
        long id;
        try { id = resolve(c); }
        catch (DataIntegrityViolationException race) { id = resolve(c); }   // two first requests at once
        if (bySubject.size() > 100_000) bySubject.clear();
        bySubject.put(c.subject(), id);
        return id;
    }

    private long resolve(SupabaseVerifier.Claims c) {
        // Same verified email, different Supabase id (account recreated in Supabase): it's the same person, relink.
        User u = users.findByAuthSubject(c.subject())
                .or(() -> c.email().isBlank() ? java.util.Optional.empty() : users.findByEmail(c.email()))
                .orElseGet(User::new);
        boolean changed = false;
        if (u.id != null && !c.subject().equals(u.authSubject)) { u.authSubject = c.subject(); changed = true; }
        if (u.id == null) {
            u.email = c.email().isBlank() ? c.subject() + "@users.noreply.codeway" : c.email();
            u.passwordHash = NO_PASSWORD;
            u.displayName = c.name().isBlank() ? u.email.split("@")[0] : c.name();
            changed = true;
        }
        if (u.authSubject == null) { u.authSubject = c.subject(); changed = true; }
        if (!c.avatarUrl().isBlank() && !Objects.equals(u.avatarUrl, c.avatarUrl())) { u.avatarUrl = c.avatarUrl(); changed = true; }
        if (changed) u = users.save(u);
        return u.id;
    }
}
