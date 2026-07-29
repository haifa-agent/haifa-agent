package io.haifa.agent.model.core;

import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelProviderId;
import java.util.Objects;
import java.util.Set;

/** Query-safe model projection for product selection surfaces. */
public record ModelView(
        ModelDefinitionId id,
        String version,
        ModelProviderId providerId,
        String displayName,
        Set<ModelCapability> capabilities,
        int contextWindow,
        int maxOutputTokens) {
    public ModelView {
        id = Objects.requireNonNull(id, "id must not be null");
        version = requireText(version, "version");
        providerId = Objects.requireNonNull(providerId, "providerId must not be null");
        displayName = requireText(displayName, "displayName");
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities must not be null"));
        if (capabilities.isEmpty()) throw new IllegalArgumentException("capabilities must not be empty");
        if (contextWindow < 1 || maxOutputTokens < 1 || maxOutputTokens > contextWindow) {
            throw new IllegalArgumentException("model token limits are invalid");
        }
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
