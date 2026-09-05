package io.haifa.agent.context.compaction;

import java.util.List;

/** Thrown when a semantic conversation summary fails quality or security gates. */
public final class SemanticSummaryValidationException extends RuntimeException {

    private final List<String> validationErrors;

    public SemanticSummaryValidationException(String message) {
        this(message, message != null ? List.of(message) : List.of());
    }

    public SemanticSummaryValidationException(String message, List<String> validationErrors) {
        super(message);
        this.validationErrors = validationErrors != null ? List.copyOf(validationErrors) : List.of();
    }

    public SemanticSummaryValidationException(String message, Throwable cause) {
        super(message, cause);
        this.validationErrors = message != null ? List.of(message) : List.of();
    }

    public List<String> validationErrors() {
        return validationErrors;
    }
}
