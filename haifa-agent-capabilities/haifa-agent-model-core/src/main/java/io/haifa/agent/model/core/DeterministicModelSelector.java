package io.haifa.agent.model.core;

import io.haifa.agent.model.api.ModelDefinition;
import io.haifa.agent.model.api.ModelProviderDefinition;
import io.haifa.agent.model.api.ModelStatus;
import io.haifa.agent.model.api.ProviderStatus;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.util.LinkedHashMap;
import java.util.Objects;

/** Selects exactly the requested model with no implicit routing or fallback. */
public final class DeterministicModelSelector {
    private final ModelCatalog catalog;
    private final ModelAccessPolicy accessPolicy;
    private final String adapterVersion;

    public DeterministicModelSelector(ModelCatalog catalog, ModelAccessPolicy accessPolicy, String adapterVersion) {
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
        this.adapterVersion = requireText(adapterVersion, "adapterVersion");
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
}
