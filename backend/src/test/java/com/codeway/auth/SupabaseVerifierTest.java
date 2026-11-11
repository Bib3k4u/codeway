package com.codeway.auth;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class SupabaseVerifierTest {
    static final String PROJECT = "https://demo.supabase.co";
    static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    final KeyPair kp = ec();
    final SupabaseVerifier v = new SupabaseVerifier(PROJECT, () -> jwks("k1", (ECPublicKey) kp.getPublic()));

    @Test void acceptsValidGithubToken() {
        var c = v.verify(token(kp, "k1", "ES256", claims(PROJECT + "/auth/v1", "authenticated", "authenticated", 3600)));
        assertTrue(c.isPresent());
        assertEquals("user-123", c.get().subject());
        assertEquals("ada@example.com", c.get().email());
        assertEquals("Ada Lovelace", c.get().name());
        assertEquals("https://avatars.example/ada.png", c.get().avatarUrl());
    }

    @Test void rejectsExpired() {
        assertTrue(v.verify(token(kp, "k1", "ES256", claims(PROJECT + "/auth/v1", "authenticated", "authenticated", -120))).isEmpty());
    }

    @Test void rejectsWrongIssuerAudienceOrRole() {
        assertTrue(v.verify(token(kp, "k1", "ES256", claims("https://evil.supabase.co/auth/v1", "authenticated", "authenticated", 3600))).isEmpty());
        assertTrue(v.verify(token(kp, "k1", "ES256", claims(PROJECT + "/auth/v1", "other", "authenticated", 3600))).isEmpty());
        assertTrue(v.verify(token(kp, "k1", "ES256", claims(PROJECT + "/auth/v1", "authenticated", "anon", 3600))).isEmpty());
    }

    @Test void rejectsForeignKeyTamperingAndWeakAlgs() {
        String good = token(kp, "k1", "ES256", claims(PROJECT + "/auth/v1", "authenticated", "authenticated", 3600));
        assertTrue(v.verify(token(ec(), "k1", "ES256", claims(PROJECT + "/auth/v1", "authenticated", "authenticated", 3600))).isEmpty(), "other key");
        String[] p = good.split("\\.");
        String forged = B64.encodeToString(claims(PROJECT + "/auth/v1", "authenticated", "authenticated", 3600).replace("user-123", "admin").getBytes(StandardCharsets.UTF_8));
        assertTrue(v.verify(p[0] + "." + forged + "." + p[2]).isEmpty(), "tampered payload");
        String none = B64.encodeToString("{\"alg\":\"none\",\"kid\":\"k1\"}".getBytes(StandardCharsets.UTF_8));
        assertTrue(v.verify(none + "." + p[1] + ".").isEmpty(), "alg none");
        String hs = B64.encodeToString("{\"alg\":\"HS256\",\"kid\":\"k1\"}".getBytes(StandardCharsets.UTF_8));
        assertTrue(v.verify(hs + "." + p[1] + "." + p[2]).isEmpty(), "HS256");
        assertTrue(v.verify("garbage").isEmpty());
    }

    static String claims(String iss, String aud, String role, long expIn) {
        long now = System.currentTimeMillis() / 1000;
        return """
            {"iss":"%s","aud":"%s","role":"%s","sub":"user-123","exp":%d,"iat":%d,"email":"Ada@Example.com",
             "user_metadata":{"full_name":"Ada Lovelace","user_name":"ada","avatar_url":"https://avatars.example/ada.png"}}
            """.formatted(iss, aud, role, now + expIn, now);
    }

    static String token(KeyPair kp, String kid, String alg, String payload) {
        try {
            String h = B64.encodeToString(("{\"alg\":\"" + alg + "\",\"kid\":\"" + kid + "\",\"typ\":\"JWT\"}").getBytes(StandardCharsets.UTF_8));
            String p = B64.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
            Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
            s.initSign(kp.getPrivate());
            s.update((h + "." + p).getBytes(StandardCharsets.US_ASCII));
            return h + "." + p + "." + B64.encodeToString(s.sign());
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    static String jwks(String kid, ECPublicKey k) {
        return "{\"keys\":[{\"kty\":\"EC\",\"crv\":\"P-256\",\"alg\":\"ES256\",\"kid\":\"" + kid + "\",\"x\":\""
                + B64.encodeToString(fixed(k.getW().getAffineX())) + "\",\"y\":\"" + B64.encodeToString(fixed(k.getW().getAffineY())) + "\"}]}";
    }

    static byte[] fixed(BigInteger n) {
        byte[] b = n.toByteArray(), out = new byte[32];
        System.arraycopy(b, Math.max(0, b.length - 32), out, Math.max(0, 32 - b.length), Math.min(32, b.length));
        return out;
    }

    static KeyPair ec() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
            return g.generateKeyPair();
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
