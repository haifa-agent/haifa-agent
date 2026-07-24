package io.haifa.agent.context.trace;

import io.haifa.agent.context.prompt.PromptComponentId;
import io.haifa.agent.context.prompt.PromptLayer;
import io.haifa.agent.context.prompt.PromptRole;
import java.util.Objects;
import java.util.Set;

/** Redacted prompt evidence. The prompt body is represented only by a content hash. */
public record PromptTraceItem(
        PromptComponentId componentId,
        PromptLayer layer,
        PromptRole role,
        String version,
        int estimatedTokens,
        String contentHash,
        Set<String> securityLabels) {
    public PromptTraceItem {
        componentId = Objects.requireNonNull(componentId, "componentId must not be null");
        layer = Objects.requireNonNull(layer, "layer must not be null");
        role = Objects.requireNonNull(role, "role must not be null");
        version = Objects.requireNonNull(version, "version must not be null");
        if (estimatedTokens < 1) throw new IllegalArgumentException("estimatedTokens must be positive");
        contentHash = Objects.requireNonNull(contentHash, "contentHash must not be null");
        securityLabels = Set.copyOf(Objects.requireNonNull(securityLabels, "securityLabels must not be null"));
    }
}
