package io.haifa.agent.context.trace;

import io.haifa.agent.context.item.ContextItemId;
import io.haifa.agent.context.item.ContextItemType;
import java.util.Objects;
import java.util.Set;

/** Redacted selection evidence; it intentionally contains no context body. */
public record ContextTraceItem(
        ContextItemId itemId,
        ContextItemType type,
        String sourceType,
        String sourceId,
        String sourceVersion,
        int estimatedTokens,
        ContextSelectionDecision decision,
        String contentHash,
        Set<String> securityLabels) {
    public ContextTraceItem {
        itemId = Objects.requireNonNull(itemId, "itemId must not be null");
        type = Objects.requireNonNull(type, "type must not be null");
        sourceType = Objects.requireNonNull(sourceType, "sourceType must not be null");
        sourceId = Objects.requireNonNull(sourceId, "sourceId must not be null");
        sourceVersion = Objects.requireNonNull(sourceVersion, "sourceVersion must not be null");
        if (estimatedTokens < 1) throw new IllegalArgumentException("estimatedTokens must be positive");
        decision = Objects.requireNonNull(decision, "decision must not be null");
        contentHash = Objects.requireNonNull(contentHash, "contentHash must not be null");
        securityLabels = Set.copyOf(Objects.requireNonNull(securityLabels, "securityLabels must not be null"));
    }
}
