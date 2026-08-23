package io.haifa.agent.cli;

import java.time.Instant;
import java.util.Objects;

/** Product-private Coding Agent model credential. Its string form never exposes secret fields. */
final class CodingAuthCredential {
    enum Kind {
        API_KEY,
        OAUTH2
    }

    private final String reference;
    private final Kind kind;
    private final String apiKey;
    private final String accessToken;
    private final String refreshToken;
    private final long expiresAtEpochMillis;
    private final String accountId;
    private final String clientRegistrationRef;
    private final long issuedAtEpochMillis;

    private CodingAuthCredential(
            String reference,
            Kind kind,
            String apiKey,
            String accessToken,
            String refreshToken,
            long expiresAtEpochMillis,
            String accountId,
            String clientRegistrationRef,
            long issuedAtEpochMillis) {
        this.reference = reference(reference);
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
        this.apiKey = secret(apiKey, kind == Kind.API_KEY, "apiKey");
        this.accessToken = secret(accessToken, kind == Kind.OAUTH2, "accessToken");
        this.refreshToken = secret(refreshToken, kind == Kind.OAUTH2, "refreshToken");
        if (kind == Kind.OAUTH2 && (expiresAtEpochMillis < 1 || issuedAtEpochMillis < 1)) {
            throw new IllegalArgumentException("OAuth credential timestamps must be positive");
        }
        this.expiresAtEpochMillis = expiresAtEpochMillis;
        this.accountId = text(accountId, kind == Kind.OAUTH2, "accountId");
        this.clientRegistrationRef = text(clientRegistrationRef, kind == Kind.OAUTH2, "clientRegistrationRef");
        this.issuedAtEpochMillis = issuedAtEpochMillis;
    }

    static CodingAuthCredential apiKey(String reference, String apiKey) {
        return new CodingAuthCredential(reference, Kind.API_KEY, apiKey, null, null, 0, null, null, 0);
    }

    static CodingAuthCredential oauth2(
            String reference,
            String accessToken,
            String refreshToken,
            long expiresAtEpochMillis,
            String accountId,
            String clientRegistrationRef,
            long issuedAtEpochMillis) {
        return new CodingAuthCredential(
                reference,
                Kind.OAUTH2,
                null,
                accessToken,
                refreshToken,
                expiresAtEpochMillis,
                accountId,
                clientRegistrationRef,
                issuedAtEpochMillis);
    }

    String reference() {
        return reference;
    }

    Kind kind() {
        return kind;
    }

    String apiKey() {
        return apiKey;
    }

    String accessToken() {
        return accessToken;
    }

    String refreshToken() {
        return refreshToken;
    }

    long expiresAtEpochMillis() {
        return expiresAtEpochMillis;
    }

    String accountId() {
        return accountId;
    }

    String clientRegistrationRef() {
        return clientRegistrationRef;
    }

    long issuedAtEpochMillis() {
        return issuedAtEpochMillis;
    }

    boolean validBeyond(Instant threshold) {
        return kind == Kind.API_KEY || expiresAtEpochMillis > threshold.toEpochMilli();
    }

    @Override
    public String toString() {
        return "CodingAuthCredential[reference=" + reference + ", kind=" + kind + ", secret=REDACTED]";
    }

    private static String reference(String value) {
        String normalized = text(value, true, "reference");
        if (normalized.length() > 128
                || !normalized.matches("[a-z0-9][a-z0-9._/-]*")
                || normalized.startsWith("/")
                || normalized.endsWith("/")
                || normalized.contains("..")) {
            throw new IllegalArgumentException("credential reference is invalid");
        }
        return normalized;
    }

    private static String secret(String value, boolean required, String field) {
        String normalized = text(value, required, field);
        if (normalized != null && normalized.length() > 64 * 1024) {
            throw new IllegalArgumentException(field + " exceeds the maximum size");
        }
        return normalized;
    }

    private static String text(String value, boolean required, String field) {
        if (value == null) {
            if (required) throw new IllegalArgumentException(field + " must not be null");
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            if (required) throw new IllegalArgumentException(field + " must not be blank");
            return null;
        }
        if (normalized.indexOf('\0') >= 0) throw new IllegalArgumentException(field + " is invalid");
        return normalized;
    }
}
