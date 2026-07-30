package io.haifa.agent.model.core;

import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelStatus;
import io.haifa.agent.model.api.ProviderHealth;
import io.haifa.agent.model.api.ProviderStatus;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable product facade with no discovery, routing, fallback or catalog mutation. */
public final class StaticModelPlatform implements ModelPlatform {
    private final ModelCatalog catalog;
    private final ModelAccessPolicy accessPolicy;
    private final Map<String, String> adapterVersions;
    private final ProviderHealthRegistry health;
    private final DeterministicModelSelector selector;

    public StaticModelPlatform(
            ModelCatalog catalog,
            ModelAccessPolicy accessPolicy,
            Map<String, String> adapterVersions,
            ProviderHealthRegistry health) {
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
        this.adapterVersions = normalizeAdapterVersions(adapterVersions);
        this.health = Objects.requireNonNull(health, "health must not be null");
        selector = new DeterministicModelSelector(catalog, accessPolicy, this.adapterVersions);
    }

    @Override
    public List<ModelProviderView> listAvailable(ModelAvailabilityRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        List<ModelProviderView> providers = new ArrayList<>();
        for (var provider : catalog.providers()) {
            if (provider.status() != ProviderStatus.ACTIVE || !adapterVersions.containsKey(provider.adapterType())) {
                continue;
            }
            List<ModelView> models = new ArrayList<>();
            for (var model : provider.models()) {
                if (model.status() != ModelStatus.ACTIVE
                        || !model.capabilities().containsAll(request.requiredCapabilities())) {
                    continue;
                }
                ModelSelectionRequest selection = new ModelSelectionRequest(
                        request.tenant(), request.principal(), model.id(), request.requiredCapabilities());
                if (!accessPolicy.allowed(selection, provider, model)) continue;
                models.add(new ModelView(
                        model.id(),
                        model.version(),
                        provider.id(),
                        model.displayName(),
                        model.capabilities(),
                        model.contextWindow(),
                        model.maxOutputTokens()));
            }
            if (models.isEmpty()) continue;
            ProviderHealth observation = health.health(provider.id());
            providers.add(new ModelProviderView(
                    provider.id(),
                    provider.version(),
                    provider.displayName(),
                    observation.status(),
                    observation.observedAt(),
                    models));
        }
        return List.copyOf(providers);
    }

    @Override
    public ResolvedModelSnapshot select(ModelSelectionRequest request) {
        return selector.select(request);
    }

    @Override
    public ProviderHealth health(ModelProviderId providerId) {
        return health.health(Objects.requireNonNull(providerId, "providerId must not be null"));
    }

    private static Map<String, String> normalizeAdapterVersions(Map<String, String> versions) {
        Objects.requireNonNull(versions, "adapterVersions must not be null");
        LinkedHashMap<String, String> normalized = new LinkedHashMap<>();
        versions.forEach((type, version) -> {
            String normalizedType = requireText(type, "adapter type");
            String normalizedVersion = requireText(version, "adapter version");
            if (normalized.putIfAbsent(normalizedType, normalizedVersion) != null) {
                throw new IllegalArgumentException("duplicate adapter type: " + normalizedType);
            }
        });
        return Map.copyOf(normalized);
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
