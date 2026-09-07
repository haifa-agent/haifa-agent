package io.haifa.agent.model.api;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Normalized result of model provider error classification. */
public record ModelErrorMapping(
        ModelErrorCategory category,
        boolean retryable,
        int httpStatus,
        String providerCode,
        String safeMessage,
        Optional<Duration> retryAfter,
        Optional<String> providerRequestId) {

    public ModelErrorMapping {
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(providerCode, "providerCode must not be null");
        Objects.requireNonNull(safeMessage, "safeMessage must not be null");
        Objects.requireNonNull(retryAfter, "retryAfter must not be null");
        Objects.requireNonNull(providerRequestId, "providerRequestId must not be null");
    }

    public static ModelErrorMapping of(
            ModelErrorCategory category, boolean retryable, int httpStatus, String providerCode, String safeMessage) {
        return new ModelErrorMapping(
                category, retryable, httpStatus, providerCode, safeMessage, Optional.empty(), Optional.empty());
    }

    public static ModelErrorMapping of(
            ModelErrorCategory category,
            boolean retryable,
            int httpStatus,
            String providerCode,
            String safeMessage,
            Optional<Duration> retryAfter) {
        return new ModelErrorMapping(
                category, retryable, httpStatus, providerCode, safeMessage, retryAfter, Optional.empty());
    }

    public static ModelErrorMapping of(
            ModelErrorCategory category,
            boolean retryable,
            int httpStatus,
            String providerCode,
            String safeMessage,
            Optional<Duration> retryAfter,
            Optional<String> providerRequestId) {
        return new ModelErrorMapping(
                category, retryable, httpStatus, providerCode, safeMessage, retryAfter, providerRequestId);
    }
}
