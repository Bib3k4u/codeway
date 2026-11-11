package com.codeway.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {
    public static final String ATTR = "userId";
    private final TokenService tokens;

    public AuthInterceptor(TokenService tokens) { this.tokens = tokens; }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        if ("OPTIONS".equals(req.getMethod())) return true;
        String h = req.getHeader("Authorization");
        if (h != null && h.startsWith("Bearer ")) {
            var id = tokens.verify(h.substring(7));
            if (id.isPresent()) { req.setAttribute(ATTR, id.get()); return true; }
        }
        res.setStatus(401);
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":\"Unauthorized\"}");
        return false;
    }
}
