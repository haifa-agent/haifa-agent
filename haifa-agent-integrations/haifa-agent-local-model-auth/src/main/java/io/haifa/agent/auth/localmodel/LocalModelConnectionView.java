package io.haifa.agent.auth.localmodel;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Safe model connection projection. It never contains credentials or client registration data. */
public record LocalModelConnectionView(
        LocalModelAuthReference connectionId,
        String providerId,
        Method method,
        Status status,
        String accountLabel,
        OptionalLong expiresAtEpochMillis,
        Optional<String> reasonCode,
        boolean unofficialLocalCompatibility) {
    public LocalModelConnectionView {
        connectionId = Objects.requireNonNull(connectionId, "connectionId must not be null");
        providerId = ExternalLoginMethodDescriptor.safeText(providerId, "providerId", 64);
        method = Objects.requireNonNull(method, "method must not be null");
        status = Objects.requireNonNull(status, "status must not be null");
        accountLabel = ExternalLoginMethodDescriptor.safeText(accountLabel, "accountLabel", 128);
        expiresAtEpochMillis = Objects.requireNonNull(expiresAtEpochMillis, "expiresAtEpochMillis must not be null");
        if (expiresAtEpochMillis.isPresent() && expiresAtEpochMillis.getAsLong() < 0) {
            throw new IllegalArgumentException("expiresAtEpochMillis must not be negative");
        }
        reasonCode = Objects.requireNonNull(reasonCode, "reasonCode must not be null")
                .map(value -> ExternalLoginMethodDescriptor.safeText(value, "reasonCode", 96));
    }

    public enum Method {
        API_KEY,
        EXTERNAL_LOGIN
    }

    public enum Status {
        AUTHENTICATED,
        REAUTH_REQUIRED,
        RATE_LIMITED
    }
}
