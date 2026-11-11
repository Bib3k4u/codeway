package com.codeway.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Verifies Supabase Auth access tokens (JWTs signed with the project's asymmetric signing key, ES256 or RS256)
 * against the project's public JWKS. No shared secret: the backend only ever holds public keys.
 * Checks signature, issuer, audience "authenticated", role "authenticated" and expiry.
 */
@Component
public class SupabaseVerifier {
    public record Claims(String subject, String email, String name, String avatarUrl) {}

    private static final ObjectMapper M = new ObjectMapper();
    private static final long SKEW_S = 30;
    private static final long JWKS_REFRESH_MIN_MS = 5 * 60_000;   // unknown kid: refetch at most every 5 min

    private final String issuer;
    private final Supplier<String> jwksSource;
    private final Map<String, PublicKey> keys = new ConcurrentHashMap<>();
    private volatile long lastFetch;

    @Autowired
    public SupabaseVerifier(@Value("${codeway.supabase.url}") String projectUrl) {
        this(projectUrl, httpJwks(projectUrl.replaceAll("/+$", "") + "/auth/v1/.well-known/jwks.json"));
    }

    /** For tests: supply the JWKS JSON directly. */
    SupabaseVerifier(String projectUrl, Supplier<String> jwksSource) {
        this.issuer = projectUrl.replaceAll("/+$", "") + "/auth/v1";
        this.jwksSource = jwksSource;
    }

    public Optional<Claims> verify(String token) {
        try {
            String[] p = token.split("\\.");
            if (p.length != 3) return Optional.empty();
            JsonNode header = M.readTree(b64(p[0]));
            String alg = header.path("alg").asText(), kid = header.path("kid").asText("");
            String javaAlg = switch (alg) {
                case "ES256" -> "SHA256withECDSAinP1363Format";
                case "RS256" -> "SHA256withRSA";
                default -> null;   // never accept "none" or the HS256 shared-secret tokens
            };
            if (javaAlg == null) return Optional.empty();
            PublicKey key = key(kid);
            if (key == null || !algMatchesKey(alg, key)) return Optional.empty();

            Signature sig = Signature.getInstance(javaAlg);
            sig.initVerify(key);
            sig.update((p[0] + "." + p[1]).getBytes(StandardCharsets.US_ASCII));
            if (!sig.verify(b64(p[2]))) return Optional.empty();

            JsonNode c = M.readTree(b64(p[1]));
            long now = System.currentTimeMillis() / 1000;
            if (!issuer.equals(c.path("iss").asText())) return Optional.empty();
            if (!audience(c.path("aud"))) return Optional.empty();
            if (!"authenticated".equals(c.path("role").asText())) return Optional.empty();
            if (!c.hasNonNull("exp") || c.path("exp").asLong() + SKEW_S < now) return Optional.empty();
            if (c.hasNonNull("nbf") && c.path("nbf").asLong() - SKEW_S > now) return Optional.empty();
            String sub = c.path("sub").asText("");
            if (sub.isBlank()) return Optional.empty();

            JsonNode meta = c.path("user_metadata");
            String email = firstNonBlank(c.path("email").asText(""), meta.path("email").asText(""));
            String name = firstNonBlank(meta.path("full_name").asText(""), meta.path("name").asText(""),
                    meta.path("user_name").asText(""), meta.path("preferred_username").asText(""));
            String avatar = firstNonBlank(meta.path("avatar_url").asText(""), meta.path("picture").asText(""));
            return Optional.of(new Claims(sub, email.toLowerCase(Locale.ROOT), name, avatar));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private PublicKey key(String kid) {
        PublicKey k = keys.get(kid);
        if (k != null) return k;
        synchronized (this) {
            k = keys.get(kid);
            long minGap = keys.isEmpty() ? 5_000 : JWKS_REFRESH_MIN_MS;   // no keys yet (startup or outage): retry sooner
            if (k == null && System.currentTimeMillis() - lastFetch > minGap) {
                lastFetch = System.currentTimeMillis();
                try { load(jwksSource.get()); } catch (Exception ignored) { }
                k = keys.get(kid);
            }
        }
        return k;
    }

    private void load(String json) throws Exception {
        for (JsonNode j : M.readTree(json).path("keys")) {
            String kid = j.path("kid").asText("");
            PublicKey k = switch (j.path("kty").asText()) {
                case "EC" -> ec(j);
                case "RSA" -> rsa(j);
                default -> null;
            };
            if (k != null) keys.put(kid, k);
        }
    }

    private static PublicKey ec(JsonNode j) throws Exception {
        if (!"P-256".equals(j.path("crv").asText())) return null;
        AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
        params.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec spec = params.getParameterSpec(ECParameterSpec.class);
        ECPoint point = new ECPoint(new BigInteger(1, b64(j.path("x").asText())), new BigInteger(1, b64(j.path("y").asText())));
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, spec));
    }

    private static PublicKey rsa(JsonNode j) throws Exception {
        return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                new BigInteger(1, b64(j.path("n").asText())), new BigInteger(1, b64(j.path("e").asText()))));
    }

    private static boolean algMatchesKey(String alg, PublicKey k) {
        return alg.startsWith("ES") ? k instanceof java.security.interfaces.ECPublicKey : k instanceof java.security.interfaces.RSAPublicKey;
    }

    private static boolean audience(JsonNode aud) {
        if (aud.isTextual()) return "authenticated".equals(aud.asText());
        if (aud.isArray()) for (JsonNode a : aud) if ("authenticated".equals(a.asText())) return true;
        return false;
    }

    private static byte[] b64(String s) { return Base64.getUrlDecoder().decode(s); }

    private static String firstNonBlank(String... v) {
        for (String s : v) if (s != null && !s.isBlank()) return s.trim();
        return "";
    }

    private static Supplier<String> httpJwks(String url) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return () -> {
            try {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 200) throw new IllegalStateException("JWKS " + r.statusCode());
                return r.body();
            } catch (Exception e) { throw new RuntimeException(e); }
        };
    }
}
