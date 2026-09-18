package io.haifa.agent.runtime.core.completion;

import java.util.Objects;

/** Structured, safe reason why a final answer cannot yet complete the Run. */
public record CompletionBlocker(String code, String safeMessage, boolean recoverable, String evidenceRequirement) {
    public CompletionBlocker {
        code = token(code, "code", 128);
        safeMessage = text(safeMessage, "safeMessage", 512);
        evidenceRequirement = text(evidenceRequirement, "evidenceRequirement", 256);
    }

    public static CompletionBlocker recoverable(String code, String safeMessage, String evidenceRequirement) {
        return new CompletionBlocker(code, safeMessage, true, evidenceRequirement);
    }

    public static CompletionBlocker terminal(String code, String safeMessage, String evidenceRequirement) {
        return new CompletionBlocker(code, safeMessage, false, evidenceRequirement);
    }

    private static String token(String value, String field, int maximumLength) {
        String normalized = text(value, field, maximumLength);
        if (!normalized.matches("[A-Z][A-Z0-9_]*")) {
            throw new IllegalArgumentException(field + " must be an upper-snake token");
        }
        return normalized;
    }

    private static String text(String value, String field, int maximumLength) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " is blank or too long");
        }
        return normalized;
    }
}
