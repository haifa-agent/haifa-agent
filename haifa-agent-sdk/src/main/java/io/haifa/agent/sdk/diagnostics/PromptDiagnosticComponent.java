package io.haifa.agent.sdk.diagnostics;

import java.util.Objects;

/** One redacted component in the actual model-context order. */
public record PromptDiagnosticComponent(
        int order,
        String componentId,
        String layer,
        String role,
        String version,
        String contentDigest,
        int estimatedTokens,
        PromptDiagnosticSource source) {
    public PromptDiagnosticComponent {
        if (order < 0) throw new IllegalArgumentException("order must not be negative");
        componentId = text(componentId, "componentId", 256);
        layer = text(layer, "layer", 64);
        role = text(role, "role", 64);
        version = text(version, "version", 256);
        contentDigest = text(contentDigest, "contentDigest", 128);
        if (!contentDigest.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("contentDigest must be a lowercase SHA-256 digest");
        }
        if (estimatedTokens < 1) throw new IllegalArgumentException("estimatedTokens must be positive");
        source = Objects.requireNonNull(source, "source must not be null");
    }

    private static String text(String value, String field, int maximumLength) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " must be non-blank and bounded");
        }
        return normalized;
    }
}
