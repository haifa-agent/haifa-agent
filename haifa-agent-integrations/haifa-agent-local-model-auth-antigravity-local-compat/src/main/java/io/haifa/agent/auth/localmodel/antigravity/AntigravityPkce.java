package io.haifa.agent.auth.localmodel.antigravity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/** PKCE S256 and OAuth state material for an Antigravity login attempt. */
public final class AntigravityPkce {
    private final SecureRandom random;

    public AntigravityPkce(SecureRandom random) {
        this.random = Objects.requireNonNull(random, "random must not be null");
    }

    public String verifier() {
        return randomToken(64);
    }

    public String state() {
        return randomToken(32);
    }

    public static String challenge(String verifier) {
        String checked = Objects.requireNonNull(verifier, "verifier must not be null");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(checked.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
