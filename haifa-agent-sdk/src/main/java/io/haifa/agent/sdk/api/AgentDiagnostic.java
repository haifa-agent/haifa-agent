package io.haifa.agent.sdk.api;

import java.util.Objects;

/** Non-secret, product-safe assembly diagnostic exposed by a built agent. */
public record AgentDiagnostic(Severity severity, String code, String safeMessage) {

    public enum Severity {
        INFO,
        WARNING,
        ERROR
    }

    public AgentDiagnostic {
        severity = Objects.requireNonNull(severity, "severity must not be null");
        code = text(code, "code");
        safeMessage = text(safeMessage, "safeMessage");
    }

    private static String text(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
