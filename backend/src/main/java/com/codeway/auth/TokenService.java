package com.codeway.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/** Minimal HMAC-signed bearer token: base64url(userId.expiryEpoch).signature */
@Service
public class TokenService {
    private final byte[] secret;
    private static final long TTL_SECONDS = 60L * 60 * 24 * 14;

    public TokenService(@Value("${codeway.auth.secret}") String secret, Environment env) {
        boolean weak = secret.length() < 32 || secret.contains("change-me") || secret.startsWith("dev-secret");
        if (weak && env.acceptsProfiles(Profiles.of("prod")))
            throw new IllegalStateException("AUTH_SECRET is missing or too weak. Set it to a random string of at least 32 characters (e.g. `openssl rand -hex 32`).");
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String issue(long userId) {
        String payload = userId + "." + (Instant.now().getEpochSecond() + TTL_SECONDS);
        String p = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return p + "." + sign(p);
    }

    public Optional<Long> verify(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 2) return Optional.empty();
            if (!MessageDigest.isEqual(sign(parts[0]).getBytes(StandardCharsets.UTF_8), parts[1].getBytes(StandardCharsets.UTF_8)))
                return Optional.empty();
            String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            String[] f = payload.split("\\.");
            if (Long.parseLong(f[1]) < Instant.now().getEpochSecond()) return Optional.empty();
            return Optional.of(Long.parseLong(f[0]));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
