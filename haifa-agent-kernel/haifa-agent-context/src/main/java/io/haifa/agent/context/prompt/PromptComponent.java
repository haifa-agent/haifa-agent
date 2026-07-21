package io.haifa.agent.context.prompt;

import java.util.Objects;
import java.util.Set;

/** Versioned instruction component; instruction data never travels in untyped middleware attributes. */
public record PromptComponent(
        PromptComponentId id,
        String version,
        PromptLayer layer,
        PromptRole role,
        String text,
        boolean removable,
        Set<String> securityLabels) {
    public PromptComponent {
        id = Objects.requireNonNull(id, "id must not be null");
        version = requireText(version, "version");
        layer = Objects.requireNonNull(layer, "layer must not be null");
        role = Objects.requireNonNull(role, "role must not be null");
        text = requireText(text, "text");
        securityLabels = Set.copyOf(Objects.requireNonNull(securityLabels, "securityLabels must not be null"));
        if (layer == PromptLayer.SYSTEM_SAFETY && removable) {
            throw new IllegalArgumentException("system safety prompts cannot be removable");
        }
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
