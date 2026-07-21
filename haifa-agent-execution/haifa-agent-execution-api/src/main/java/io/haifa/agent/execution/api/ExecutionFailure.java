package io.haifa.agent.execution.api;

import java.util.Objects;

public record ExecutionFailure(String code, String safeDetail) {
    public ExecutionFailure {
        code = require(code, "code");
        safeDetail = require(safeDetail, "safeDetail");
    }

    private static String require(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
