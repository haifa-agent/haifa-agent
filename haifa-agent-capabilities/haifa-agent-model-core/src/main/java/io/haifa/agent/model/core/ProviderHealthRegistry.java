package io.haifa.agent.model.core;

import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ProviderHealth;

/** Read boundary for current provider health observations. */
@FunctionalInterface
public interface ProviderHealthRegistry {
    ProviderHealth health(ModelProviderId providerId);
}
