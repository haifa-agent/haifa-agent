package io.haifa.agent.model.core;

import io.haifa.agent.model.api.ModelBindingProfile;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelProviderDefinition;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable product projection of catalog facts combined with one deployment. */
public final class ModelCatalogProjection {
    private final List<ModelProviderDefinition> providers;
    private final Map<ModelDefinitionId, ModelCatalogBinding> bindings;
    private final Map<ModelDefinitionId, ModelBindingProfile> profiles;

    ModelCatalogProjection(List<ModelProviderDefinition> providers, List<ModelCatalogBinding> bindings) {
        this.providers = List.copyOf(Objects.requireNonNull(providers, "providers must not be null"));
        LinkedHashMap<ModelDefinitionId, ModelCatalogBinding> bindingIndex = new LinkedHashMap<>();
        LinkedHashMap<ModelDefinitionId, ModelBindingProfile> profileIndex = new LinkedHashMap<>();
        for (ModelCatalogBinding binding : List.copyOf(Objects.requireNonNull(bindings, "bindings must not be null"))) {
            if (bindingIndex.putIfAbsent(binding.definition().id(), binding) != null) {
                throw new IllegalArgumentException("duplicate projected binding id: "
                        + binding.definition().id());
            }
            profileIndex.put(binding.definition().id(), binding.profile());
        }
        this.bindings = Map.copyOf(bindingIndex);
        this.profiles = Map.copyOf(profileIndex);
    }

    public List<ModelProviderDefinition> providers() {
        return providers;
    }

    public Optional<ModelCatalogBinding> binding(String bindingId) {
        return Optional.ofNullable(bindings.get(new ModelDefinitionId(bindingId)));
    }

    public Map<ModelDefinitionId, ModelBindingProfile> profiles() {
        return profiles;
    }
}
