package io.haifa.agent.sandbox.api;

import java.util.Objects;

public class SandboxException extends RuntimeException {
    private final String code;

    public SandboxException(String code, String safeMessage) {
        super(require(safeMessage, "safeMessage"));
        this.code = require(code, "code");
    }

    public String code() {
        return code;
    }

    private static String require(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty() || normalized.length() > 256 || normalized.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }
}
