package io.haifa.agent.application.project.product.coding.client;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Safe Coding Agent authentication projection. It never contains credentials or authorization URLs. */
public record CodingAuthenticationView(
        String connectionId,
        String providerId,
        Method method,
        Status status,
        String accountLabel,
        Optional<String> planType,
        OptionalLong resetAtEpochMillis,
        boolean unofficialLocalCompatibility) {
    public CodingAuthenticationView {
        connectionId = text(connectionId, "connectionId");
        providerId = text(providerId, "providerId");
        method = Objects.requireNonNull(method, "method must not be null");
        status = Objects.requireNonNull(status, "status must not be null");
        accountLabel = text(accountLabel, "accountLabel");
        planType = Objects.requireNonNull(planType, "planType must not be null").map(value -> text(value, "planType"));
        resetAtEpochMillis = Objects.requireNonNull(resetAtEpochMillis, "resetAtEpochMillis must not be null");
        if (resetAtEpochMillis.isPresent() && resetAtEpochMillis.getAsLong() < 0) {
            throw new IllegalArgumentException("resetAtEpochMillis must not be negative");
        }
    }

    public enum Method {
        API_KEY,
        CHATGPT_SUBSCRIPTION
    }

    public enum Status {
        AUTHENTICATED,
        REAUTH_REQUIRED,
        RATE_LIMITED
    }

    private static String text(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty() || normalized.length() > 256) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }
}
