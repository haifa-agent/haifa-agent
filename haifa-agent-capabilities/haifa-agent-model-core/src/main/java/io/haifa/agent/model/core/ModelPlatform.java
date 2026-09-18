package io.haifa.agent.model.core;

import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ProviderHealth;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.util.List;

/** Small product-facing facade over static catalog, deterministic selection and transient health. */
public interface ModelPlatform {
    List<ModelProviderView> listAvailable(ModelAvailabilityRequest request);

    ResolvedModelSnapshot select(ModelSelectionRequest request);

    ProviderHealth health(ModelProviderId providerId);
}
