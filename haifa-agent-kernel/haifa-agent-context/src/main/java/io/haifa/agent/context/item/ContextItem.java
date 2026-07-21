package io.haifa.agent.context.item;

import java.util.Map;
import java.util.Objects;

/** Closed, auditable Context value; arbitrary Object payloads are not permitted. */
public record ContextItem(
        ContextItemId id,
        ContextItemType type,
        ContextContent content,
        int estimatedTokens,
        ContextPriority priority,
        ContextRetention retention,
        ContextSecurity security,
        ContextProvenance provenance,
        Map<String, String> metadata) {
    public ContextItem {
        id = Objects.requireNonNull(id, "id must not be null");
        type = Objects.requireNonNull(type, "type must not be null");
        content = Objects.requireNonNull(content, "content must not be null");
        if (estimatedTokens < 1) throw new IllegalArgumentException("estimatedTokens must be positive");
        priority = Objects.requireNonNull(priority, "priority must not be null");
        retention = Objects.requireNonNull(retention, "retention must not be null");
        security = Objects.requireNonNull(security, "security must not be null");
        provenance = Objects.requireNonNull(provenance, "provenance must not be null");
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata must not be null"));
    }
}
