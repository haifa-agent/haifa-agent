package io.haifa.agent.model.openai.responses;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Narrow, validated identity projection for OpenAI Codex requests. */
public final class CodexAccountIdentity {
    private final String accountId;

    public CodexAccountIdentity(String accountId) {
        this.accountId = validate(accountId);
    }

    public String accountId() {
        return accountId;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof CodexAccountIdentity other)) return false;
        return accountId.equals(other.accountId);
    }

    @Override
    public int hashCode() {
        return accountId.hashCode();
    }

    @Override
    public String toString() {
        return "CodexAccountIdentity[fingerprint=" + fingerprint(accountId) + "]";
    }

    private static String validate(String value) {
        String normalized =
                Objects.requireNonNull(value, "accountId must not be null").trim();
        if (!normalized.matches("[A-Za-z0-9_-]{1,256}")) {
            throw new IllegalArgumentException("Codex account ID format is invalid");
        }
        return normalized;
    }

    private static String fingerprint(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
