package io.haifa.agent.contract.error;

import io.haifa.agent.contract.common.ApiVersion;
import java.time.Instant;
import java.util.Objects;

/** Stable external error envelope independent of internal exception types. */
public record ErrorResponse(
        ApiVersion apiVersion, String code, String message, String correlationId, Instant timestamp) {

    public ErrorResponse {
        apiVersion = Objects.requireNonNull(apiVersion, "apiVersion must not be null");
        code = requireText(code, "code");
        message = requireText(message, "message");
        correlationId = requireText(correlationId, "correlationId");
        timestamp = Objects.requireNonNull(timestamp, "timestamp must not be null");
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
