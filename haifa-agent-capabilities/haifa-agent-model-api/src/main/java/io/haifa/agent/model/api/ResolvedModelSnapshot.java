package io.haifa.agent.model.api;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable model selection frozen into one run configuration. */
public record ResolvedModelSnapshot(
        ModelProviderId providerId,
        ModelDefinitionId modelId,
        String providerModelId,
        String adapterType,
        String adapterVersion,
        CredentialRef credentialRef,
        Set<ModelCapability> capabilities,
        Map<String, Object> invocationOptions,
        String configurationDigest) {
    public ResolvedModelSnapshot {
        providerId = Objects.requireNonNull(providerId, "providerId must not be null");
        modelId = Objects.requireNonNull(modelId, "modelId must not be null");
        providerModelId = ModelValues.text(providerModelId, "providerModelId");
        adapterType = ModelValues.text(adapterType, "adapterType");
        adapterVersion = ModelValues.text(adapterVersion, "adapterVersion");
        credentialRef = Objects.requireNonNull(credentialRef, "credentialRef must not be null");
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities must not be null"));
        invocationOptions = ModelValues.map(invocationOptions, "invocationOptions");
        configurationDigest = ModelValues.text(configurationDigest, "configurationDigest");
    }
}
