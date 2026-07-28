package io.haifa.agent.sdk.api;

import java.util.Objects;

/** Stable, non-secret SDK error envelope. */
public class HaifaAgentException extends IllegalStateException {
    private final String code;
    private final String operation;
    private final String correlation;

    public HaifaAgentException(String code, String operation, String correlation, String safeMessage) {
        this(code, operation, correlation, safeMessage, null);
    }

    public HaifaAgentException(String code, String operation, String correlation, String safeMessage, Throwable cause) {
        super(text(safeMessage, "safeMessage", 512), cause);
        this.code = text(code, "code", 128);
        this.operation = text(operation, "operation", 128);
        this.correlation = text(correlation, "correlation", 128);
    }

    public final String code() {
        return code;
    }

    public final String operation() {
        return operation;
    }

    public final String correlation() {
        return correlation;
    }

    private static String text(String value, String field, int maximumLength) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " must contain 1 to " + maximumLength + " characters");
        }
        return normalized;
    }
}
