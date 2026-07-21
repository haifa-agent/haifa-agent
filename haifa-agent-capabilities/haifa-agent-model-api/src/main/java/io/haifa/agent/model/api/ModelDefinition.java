package io.haifa.agent.model.api;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** A governed model entry owned by exactly one provider. */
public record ModelDefinition(
        ModelDefinitionId id,
        String version,
        ModelProviderId providerId,
        String providerModelId,
        String displayName,
        ModelStatus status,
        Set<ModelCapability> capabilities,
        int contextWindow,
        int maxOutputTokens,
        Map<String, Object> options,
        Map<String, Object> metadata) {
    public ModelDefinition {
        id = Objects.requireNonNull(id, "id must not be null");
        version = ModelValues.text(version, "version");
        providerId = Objects.requireNonNull(providerId, "providerId must not be null");
        providerModelId = ModelValues.text(providerModelId, "providerModelId");
        displayName = ModelValues.text(displayName, "displayName");
        status = Objects.requireNonNull(status, "status must not be null");
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities must not be null"));
        if (capabilities.isEmpty()) throw new IllegalArgumentException("capabilities must not be empty");
        if (contextWindow < 1 || maxOutputTokens < 1 || maxOutputTokens > contextWindow) {
            throw new IllegalArgumentException("model token limits are invalid");
        }
        options = ModelValues.map(options, "options");
        metadata = ModelValues.map(metadata, "metadata");
    }
}
