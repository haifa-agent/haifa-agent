package io.haifa.agent.model.core;

import io.haifa.agent.model.api.ModelDefinition;
import io.haifa.agent.model.api.ModelProviderDefinition;

/** Tenant/principal authorization applied before a model is frozen into a run. */
@FunctionalInterface
public interface ModelAccessPolicy {
    boolean allowed(ModelSelectionRequest request, ModelProviderDefinition provider, ModelDefinition model);

    static ModelAccessPolicy allowAll() {
        return (request, provider, model) -> true;
    }
}
