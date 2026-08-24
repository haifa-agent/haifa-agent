package io.haifa.agent.auth.localmodel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Versioned secret payload produced by an allowlisted external login method. */
public final class StoredExternalCredential implements StoredModelCredential {
    private final LocalModelAuthReference reference;
    private final ExternalLoginMethodId methodId;
    private final String clientRegistrationRef;
    private final String accessToken;
    private final String refreshToken;
    private final long expiresAtEpochMillis;
    private final long issuedAtEpochMillis;
    private final String accountId;

    public StoredExternalCredential(
            LocalModelAuthReference reference,
            ExternalLoginMethodId methodId,
            String clientRegistrationRef,
            String accessToken,
            String refreshToken,
            long expiresAtEpochMillis,
            long issuedAtEpochMillis,
            String accountId) {
        this.reference = Objects.requireNonNull(reference, "reference must not be null");
        this.methodId = Objects.requireNonNull(methodId, "methodId must not be null");
        this.clientRegistrationRef = safeIdentity(clientRegistrationRef, "clientRegistrationRef", 128);
        this.accessToken = StoredApiKeyCredential.secret(accessToken, "accessToken");
        this.refreshToken = StoredApiKeyCredential.secret(refreshToken, "refreshToken");
        if (expiresAtEpochMillis < 1 || issuedAtEpochMillis < 1 || expiresAtEpochMillis <= issuedAtEpochMillis) {
            throw new IllegalArgumentException("external credential timestamps are invalid");
        }
        this.expiresAtEpochMillis = expiresAtEpochMillis;
        this.issuedAtEpochMillis = issuedAtEpochMillis;
        this.accountId = safeIdentity(accountId, "accountId", 256);
    }

    @Override
    public LocalModelAuthReference reference() {
        return reference;
    }

    public ExternalLoginMethodId methodId() {
        return methodId;
    }

    public String clientRegistrationRef() {
        return clientRegistrationRef;
    }

    public String accessToken() {
        return accessToken;
    }

    public String refreshToken() {
        return refreshToken;
    }

    public long expiresAtEpochMillis() {
        return expiresAtEpochMillis;
    }

    public long issuedAtEpochMillis() {
        return issuedAtEpochMillis;
    }

    public String accountId() {
        return accountId;
    }

    public boolean validBeyond(Instant instant) {
        return expiresAtEpochMillis
                > Objects.requireNonNull(instant, "instant must not be null").toEpochMilli();
    }

    @Override
    public LocalModelConnectionView safeView(boolean unofficialLocalCompatibility) {
        return new LocalModelConnectionView(
                reference,
                reference.providerId(),
                LocalModelConnectionView.Method.EXTERNAL_LOGIN,
                LocalModelConnectionView.Status.AUTHENTICATED,
                "Account " + accountFingerprint(accountId),
                OptionalLong.of(expiresAtEpochMillis),
                Optional.empty(),
                unofficialLocalCompatibility);
    }

    @Override
    public String toString() {
        return "StoredExternalCredential[reference=" + reference + ", methodId=" + methodId
                + ", clientRegistrationRef=<redacted>, accessToken=<redacted>, refreshToken=<redacted>, accountId="
                + accountFingerprint(accountId) + "]";
    }

    private static String safeIdentity(String value, String field, int limit) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()
                || normalized.length() > limit
                || normalized.indexOf('\0') >= 0
                || normalized.indexOf('\r') >= 0
                || normalized.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static String accountFingerprint(String accountId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(accountId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
