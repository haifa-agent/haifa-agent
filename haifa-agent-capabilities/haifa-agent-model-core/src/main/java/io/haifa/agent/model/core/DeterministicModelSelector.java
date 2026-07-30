package io.haifa.agent.model.core;

import io.haifa.agent.model.api.ModelDefinition;
import io.haifa.agent.model.api.ModelProviderDefinition;
import io.haifa.agent.model.api.ModelStatus;
import io.haifa.agent.model.api.ProviderStatus;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Selects exactly the requested model with no implicit routing or fallback. */
public final class DeterministicModelSelector {
    private final ModelCatalog catalog;
    private final ModelAccessPolicy accessPolicy;
    private final Function<String, String> adapterVersionResolver;

    public DeterministicModelSelector(ModelCatalog catalog, ModelAccessPolicy accessPolicy, String adapterVersion) {
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
        String fixedVersion = requireText(adapterVersion, "adapterVersion");
        adapterVersionResolver = ignored -> fixedVersion;
    }

    public DeterministicModelSelector(
            ModelCatalog catalog, ModelAccessPolicy accessPolicy, Map<String, String> adapterVersions) {
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
        Map<String, String> frozenVersions = normalizeAdapterVersions(adapterVersions);
        adapterVersionResolver = frozenVersions::get;
    }

    public ResolvedModelSnapshot select(ModelSelectionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        ModelDefinition model = catalog.model(request.modelId())
                .orElseThrow(() -> new ModelSelectionException(
                        ModelSelectionFailure.MODEL_NOT_FOUND, "model is not configured: " + request.modelId()));
        ModelProviderDefinition provider = catalog.provider(model.providerId())
                .orElseThrow(() -> new ModelSelectionException(
                        ModelSelectionFailure.PROVIDER_NOT_FOUND, "provider is not configured: " + model.providerId()));
        if (provider.status() != ProviderStatus.ACTIVE) {
            throw new ModelSelectionException(
                    ModelSelectionFailure.PROVIDER_NOT_ACTIVE, "provider is not active: " + provider.id());
        }
        if (model.status() != ModelStatus.ACTIVE) {
            throw new ModelSelectionException(
                    ModelSelectionFailure.MODEL_NOT_ACTIVE, "model is not active: " + model.id());
        }
        if (!model.capabilities().containsAll(request.requiredCapabilities())) {
            throw new ModelSelectionException(
                    ModelSelectionFailure.CAPABILITY_MISMATCH, "model does not satisfy required capabilities");
        }
        if (!accessPolicy.allowed(request, provider, model)) {
            throw new ModelSelectionException(ModelSelectionFailure.ACCESS_DENIED, "model access is denied");
        }
        String adapterVersion = adapterVersionResolver.apply(provider.adapterType());
        if (adapterVersion == null) {
            throw new ModelSelectionException(
                    ModelSelectionFailure.ADAPTER_NOT_AVAILABLE,
                    "model adapter is not configured: " + provider.adapterType());
        }
        LinkedHashMap<String, Object> invocationOptions = new LinkedHashMap<>(model.options());
        return ResolvedModelSnapshot.create(
                provider.id(),
                provider.version(),
                model.id(),
                model.version(),
                model.providerModelId(),
                provider.adapterType(),
                adapterVersion,
                provider.endpoint(),
                provider.credentialRef(),
                model.capabilities(),
                model.contextWindow(),
                model.maxOutputTokens(),
                provider.options(),
                invocationOptions);
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
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
}
