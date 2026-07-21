package io.haifa.agent.model.api;

import java.time.Instant;
import java.util.Objects;

/** One transient, non-configuration provider health observation. */
public record ProviderHealth(
        ModelProviderId providerId, ProviderHealthStatus status, String detail, Instant observedAt) {
    public ProviderHealth {
        providerId = Objects.requireNonNull(providerId, "providerId must not be null");
        status = Objects.requireNonNull(status, "status must not be null");
        detail = ModelValues.text(detail, "detail");
        observedAt = Objects.requireNonNull(observedAt, "observedAt must not be null");
    }
}
