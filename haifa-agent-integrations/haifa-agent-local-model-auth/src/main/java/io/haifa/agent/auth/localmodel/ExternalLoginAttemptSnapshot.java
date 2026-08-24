package io.haifa.agent.auth.localmodel;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

/** Secret-free snapshot of one login attempt. Browser authorization URLs never enter this DTO. */
public record ExternalLoginAttemptSnapshot(
        ExternalLoginAttemptId attemptId,
        ExternalLoginMethodId methodId,
        ExternalLoginMode mode,
        ExternalLoginAttemptState state,
        Optional<URI> verificationUri,
        Optional<String> userCode,
        long expiresAtEpochMillis,
        Optional<String> reasonCode) {
    public ExternalLoginAttemptSnapshot {
        attemptId = Objects.requireNonNull(attemptId, "attemptId must not be null");
        methodId = Objects.requireNonNull(methodId, "methodId must not be null");
        mode = Objects.requireNonNull(mode, "mode must not be null");
        state = Objects.requireNonNull(state, "state must not be null");
        verificationUri = Objects.requireNonNull(verificationUri, "verificationUri must not be null")
                .map(ExternalLoginAttemptSnapshot::safeVerificationUri);
        userCode = Objects.requireNonNull(userCode, "userCode must not be null")
                .map(value -> ExternalLoginMethodDescriptor.safeText(value, "userCode", 32));
        if (expiresAtEpochMillis < 0) throw new IllegalArgumentException("expiresAtEpochMillis must not be negative");
        reasonCode = Objects.requireNonNull(reasonCode, "reasonCode must not be null")
                .map(value -> ExternalLoginMethodDescriptor.safeText(value, "reasonCode", 96));
    }

    public ExternalLoginAttemptSnapshot withState(ExternalLoginAttemptState next, Optional<String> reason) {
        return new ExternalLoginAttemptSnapshot(
                attemptId, methodId, mode, next, verificationUri, userCode, expiresAtEpochMillis, reason);
    }

    private static URI safeVerificationUri(URI uri) {
        URI value =
                Objects.requireNonNull(uri, "verificationUri must not be null").normalize();
        if (!value.isAbsolute()
                || value.getHost() == null
                || value.getRawUserInfo() != null
                || value.getRawQuery() != null
                || value.getRawFragment() != null
                || !("https".equalsIgnoreCase(value.getScheme()) || "http".equalsIgnoreCase(value.getScheme()))) {
            throw new IllegalArgumentException("verificationUri is unsafe");
        }
        return value;
    }
}
