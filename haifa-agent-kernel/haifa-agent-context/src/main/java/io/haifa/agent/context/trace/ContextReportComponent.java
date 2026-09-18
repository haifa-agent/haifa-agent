package io.haifa.agent.context.trace;

import io.haifa.agent.context.prompt.PromptLayer;
import io.haifa.agent.context.prompt.PromptRole;
import java.util.Objects;
import java.util.Set;

/** One redacted component that actually enters the assembled context. */
public record ContextReportComponent(
        String id,
        ComponentKind kind,
        String sourceType,
        String sourceId,
        PromptLayer layer,
        PromptRole role,
        String version,
        int estimatedTokens,
        String contentHash,
        Set<String> securityLabels) {
    public ContextReportComponent {
        id = required(id, "id");
        kind = Objects.requireNonNull(kind, "kind must not be null");
        sourceType = required(sourceType, "sourceType");
        sourceId = required(sourceId, "sourceId");
        version = required(version, "version");
        if (estimatedTokens < 1) throw new IllegalArgumentException("estimatedTokens must be positive");
        contentHash = required(contentHash, "contentHash");
        securityLabels = Set.copyOf(Objects.requireNonNull(securityLabels, "securityLabels must not be null"));
        if (kind == ComponentKind.PROMPT && (layer == null || role == null)) {
            throw new IllegalArgumentException("prompt components require layer and role");
        }
        if (kind == ComponentKind.CONTEXT && (layer != null || role != null)) {
            throw new IllegalArgumentException("context components cannot declare prompt layer or role");
        }
    }

    public enum ComponentKind {
        PROMPT,
        CONTEXT
    }

    private static String required(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
