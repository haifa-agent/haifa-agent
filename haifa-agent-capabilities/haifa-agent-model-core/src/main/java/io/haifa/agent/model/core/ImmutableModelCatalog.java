package io.haifa.agent.model.core;

import io.haifa.agent.model.api.ModelDefinition;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelProviderDefinition;
import io.haifa.agent.model.api.ModelProviderId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Validated immutable catalog preserving provider and model declaration order. */
public final class ImmutableModelCatalog implements ModelCatalog {
    private final List<ModelProviderDefinition> providers;
    private final Map<ModelProviderId, ModelProviderDefinition> providersById;
    private final Map<ModelDefinitionId, ModelDefinition> modelsById;

    public ImmutableModelCatalog(List<ModelProviderDefinition> providers) {
        this.providers = List.copyOf(Objects.requireNonNull(providers, "providers must not be null"));
        if (this.providers.isEmpty()) throw new IllegalArgumentException("providers must not be empty");
        LinkedHashMap<ModelProviderId, ModelProviderDefinition> providerIndex = new LinkedHashMap<>();
        LinkedHashMap<ModelDefinitionId, ModelDefinition> modelIndex = new LinkedHashMap<>();
        for (ModelProviderDefinition provider : this.providers) {
            Objects.requireNonNull(provider, "provider must not be null");
            if (providerIndex.putIfAbsent(provider.id(), provider) != null) {
                throw new IllegalArgumentException("duplicate provider id: " + provider.id());
            }
            for (ModelDefinition model : provider.models()) {
                if (modelIndex.putIfAbsent(model.id(), model) != null) {
                    throw new IllegalArgumentException("duplicate model id: " + model.id());
                }
            }
        }
        providersById = Map.copyOf(providerIndex);
        modelsById = Map.copyOf(modelIndex);
    }

    @Override
    public List<ModelProviderDefinition> providers() {
        return providers;
    }

    @Override
    public Optional<ModelProviderDefinition> provider(ModelProviderId id) {
        return Optional.ofNullable(providersById.get(Objects.requireNonNull(id)));
    }

    @Override
    public Optional<ModelDefinition> model(ModelDefinitionId id) {
        return Optional.ofNullable(modelsById.get(Objects.requireNonNull(id)));
    }
}
