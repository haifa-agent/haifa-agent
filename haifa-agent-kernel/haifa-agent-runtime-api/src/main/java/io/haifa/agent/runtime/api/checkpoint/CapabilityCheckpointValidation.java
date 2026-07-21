package io.haifa.agent.runtime.api.checkpoint;

import java.util.Objects;

public record CapabilityCheckpointValidation(boolean valid, String code, String message) {
    public CapabilityCheckpointValidation {
        code = requireText(code, "code");
        message = requireText(message, "message");
    }

    public static CapabilityCheckpointValidation accepted() {
        return new CapabilityCheckpointValidation(true, "VALID", "capability checkpoint is valid");
    }

    public static CapabilityCheckpointValidation rejected(String code, String message) {
        return new CapabilityCheckpointValidation(false, code, message);
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
