package com.codeway.config;

import com.codeway.auth.AuthInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final AuthInterceptor auth;
    private final RateLimitInterceptor rateLimit;
    @Value("${codeway.cors.origins:http://localhost:*,http://127.0.0.1:*}") private String[] corsOrigins;

    public WebConfig(AuthInterceptor auth, RateLimitInterceptor rateLimit) { this.auth = auth; this.rateLimit = rateLimit; }

    @Override
    public void addInterceptors(InterceptorRegistry r) {
        r.addInterceptor(auth).addPathPatterns("/api/**").excludePathPatterns("/api/auth/register", "/api/auth/login", "/api/health");
        r.addInterceptor(rateLimit).addPathPatterns("/api/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry r) {
        r.addMapping("/api/**").allowedOriginPatterns(corsOrigins).allowedMethods("*").allowedHeaders("*");
    }

    @Bean BCryptPasswordEncoder encoder() { return new BCryptPasswordEncoder(); }
}
