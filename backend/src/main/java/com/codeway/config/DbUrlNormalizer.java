package com.codeway.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Accepts DB_URL as a plain JDBC URL or as a provider-style URL (postgresql://user:pass@host/db?sslmode=require),
 * e.g. the connection string copied from Neon. Converts it to JDBC form and fills in credentials if DB_USER is empty.
 */
public class DbUrlNormalizer implements EnvironmentPostProcessor, Ordered {
    private static final Set<String> UNSUPPORTED_PARAMS = Set.of("channel_binding");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        String raw = env.getProperty("spring.datasource.url");
        if (raw == null || !(raw.startsWith("postgresql://") || raw.startsWith("postgres://"))) return;
        URI u = URI.create(raw.replaceFirst("^postgres(ql)?://", "http://"));
        Map<String, Object> out = new HashMap<>();
        String query = u.getRawQuery() == null ? "" : Arrays.stream(u.getRawQuery().split("&"))
                .filter(p -> !UNSUPPORTED_PARAMS.contains(p.split("=")[0])).collect(Collectors.joining("&"));
        out.put("spring.datasource.url", "jdbc:postgresql://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "")
                + u.getRawPath() + (query.isEmpty() ? "" : "?" + query));
        if (u.getRawUserInfo() != null) {
            String[] ui = u.getRawUserInfo().split(":", 2);
            String user = env.getProperty("spring.datasource.username", "");
            String pass = env.getProperty("spring.datasource.password", "");
            if (user.isBlank() || "postgres".equals(user)) out.put("spring.datasource.username", URLDecoder.decode(ui[0], StandardCharsets.UTF_8));
            if (pass.isBlank() && ui.length > 1) out.put("spring.datasource.password", URLDecoder.decode(ui[1], StandardCharsets.UTF_8));
        }
        env.getPropertySources().addFirst(new MapPropertySource("normalizedDbUrl", out));
    }

    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }
}
