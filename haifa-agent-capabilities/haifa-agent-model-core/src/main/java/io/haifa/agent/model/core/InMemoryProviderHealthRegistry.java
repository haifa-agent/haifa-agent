package io.haifa.agent.model.core;

import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ProviderHealth;
import io.haifa.agent.model.api.ProviderHealthStatus;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Transient health observations kept outside provider configuration. */
public final class InMemoryProviderHealthRegistry {
    private final Map<ModelProviderId, ProviderHealth> health = new ConcurrentHashMap<>();

    public ProviderHealth health(ModelProviderId providerId) {
        Objects.requireNonNull(providerId, "providerId must not be null");
        return health.getOrDefault(
                providerId, new ProviderHealth(providerId, ProviderHealthStatus.UNKNOWN, "not checked", Instant.EPOCH));
    }

    public void update(ProviderHealth observation) {
        health.put(
                Objects.requireNonNull(observation, "observation must not be null")
                        .providerId(),
                observation);
    }
}
