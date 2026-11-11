package com.codeway.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Accepts only Supabase access tokens (GitHub sign-in). Sets the Codeway user id as a request attribute. */
@Component
public class AuthInterceptor implements HandlerInterceptor {
    public static final String ATTR = "userId";
    private final SupabaseVerifier verifier;
    private final AccountService accounts;

    public AuthInterceptor(SupabaseVerifier verifier, AccountService accounts) { this.verifier = verifier; this.accounts = accounts; }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        if ("OPTIONS".equals(req.getMethod())) return true;
        String h = req.getHeader("Authorization");
        if (h != null && h.startsWith("Bearer ")) {
            var claims = verifier.verify(h.substring(7).trim());
            if (claims.isPresent()) {
                long id = accounts.userId(claims.get());
                req.setAttribute(ATTR, id);
                CurrentUser.set(id);
                return true;
            }
        }
        res.setStatus(401);
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":\"Unauthorized\"}");
        return false;
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object handler, Exception ex) {
        CurrentUser.clear();
    }
}
