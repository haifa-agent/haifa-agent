package io.haifa.agent.tool.api;

import java.util.Objects;

/** Persisted, provider-neutral reconciliation observation. */
public record ToolReconciliationRecord(ToolReconciliationStatus status, String reasonCode) {
    public ToolReconciliationRecord {
        status = Objects.requireNonNull(status, "status must not be null");
        reasonCode = stableCode(reasonCode);
    }

    static String stableCode(String value) {
        String normalized =
                Objects.requireNonNull(value, "reasonCode must not be null").trim();
        if (normalized.isEmpty() || normalized.length() > 128 || !normalized.matches("[A-Z][A-Z0-9_]*")) {
            throw new IllegalArgumentException("reasonCode must be bounded upper-snake text");
        }
        return normalized;
    }
}
