package io.haifa.agent.context.compression;

import java.util.List;

/** Thrown when a semantic conversation summary fails quality or security gates. */
public final class SemanticSummaryValidationException extends RuntimeException {

    /** Safe classifications; diagnostic messages and validationErrors are not safe telemetry. */
    public enum Code {
        SCHEMA,
        SOURCE_REFERENCE,
        STABLE_ID,
        CARRY_FORWARD,
        SAFETY,
        UNEXPECTED_FINISH_REASON,
        JSON_PARSE_ERROR,
        EMPTY_STRUCTURED_OUTPUT,
        SCHEMA_MAPPING_ERROR
    }

    private final List<String> validationErrors;
    private final Code code;

    public SemanticSummaryValidationException(String message) {
        this(message, message != null ? List.of(message) : List.of());
    }

    public SemanticSummaryValidationException(String message, List<String> validationErrors) {
        this(Code.SCHEMA, message, validationErrors);
    }

    public SemanticSummaryValidationException(Code code, String message) {
        this(code, message, message != null ? List.of(message) : List.of());
    }

    public SemanticSummaryValidationException(Code code, String message, List<String> validationErrors) {
        super(message);
        this.code = java.util.Objects.requireNonNull(code, "code must not be null");
        this.validationErrors = validationErrors != null ? List.copyOf(validationErrors) : List.of();
    }

    public SemanticSummaryValidationException(String message, Throwable cause) {
        super(message, cause);
        this.code = Code.SCHEMA;
        this.validationErrors = message != null ? List.of(message) : List.of();
    }

    public List<String> validationErrors() {
        return validationErrors;
    }

    public Code code() {
        return code;
    }
}
