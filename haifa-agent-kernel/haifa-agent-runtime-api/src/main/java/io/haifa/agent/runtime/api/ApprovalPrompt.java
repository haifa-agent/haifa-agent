package io.haifa.agent.runtime.api;

import java.util.Objects;
import java.util.Optional;

/**
 * Product formatter output for one approval request: the authoritative fallback text plus an optional
 * structured presentation for product surfaces.
 */
public record ApprovalPrompt(String prompt, Optional<ApprovalPresentation> presentation) {
    public ApprovalPrompt {
        prompt = requireText(prompt, "prompt");
        presentation = Objects.requireNonNull(presentation, "presentation must not be null");
    }

    public static ApprovalPrompt of(String prompt) {
        return new ApprovalPrompt(prompt, Optional.empty());
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
