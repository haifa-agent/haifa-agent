package io.haifa.agent.model.core;

import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ProviderHealthStatus;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Query-safe provider projection with current non-routing health information. */
public record ModelProviderView(
        ModelProviderId id,
        String version,
        String displayName,
        ProviderHealthStatus healthStatus,
        Instant healthObservedAt,
        List<ModelView> models) {
    public ModelProviderView {
        id = Objects.requireNonNull(id, "id must not be null");
        version = requireText(version, "version");
        displayName = requireText(displayName, "displayName");
        healthStatus = Objects.requireNonNull(healthStatus, "healthStatus must not be null");
        healthObservedAt = Objects.requireNonNull(healthObservedAt, "healthObservedAt must not be null");
        models = List.copyOf(Objects.requireNonNull(models, "models must not be null"));
        if (models.isEmpty()) throw new IllegalArgumentException("models must not be empty");
        for (ModelView model : models) {
            if (!id.equals(model.providerId())) {
                throw new IllegalArgumentException("model belongs to another provider");
            }
        }
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
