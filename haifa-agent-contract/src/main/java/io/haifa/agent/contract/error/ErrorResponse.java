package io.haifa.agent.contract.error;

import io.haifa.agent.common.time.TimePrecision;
import io.haifa.agent.contract.common.ApiVersion;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Stable external error envelope independent of internal exception types. */
public record ErrorResponse(
        ApiVersion apiVersion,
        ErrorCode code,
        String message,
        String correlationId,
        Instant timestamp,
        List<FieldViolation> violations) {

    public ErrorResponse(ApiVersion apiVersion, String code, String message, String correlationId, Instant timestamp) {
        this(apiVersion, new ErrorCode(code), message, correlationId, timestamp, List.of());
    }

    public ErrorResponse {
        apiVersion = Objects.requireNonNull(apiVersion, "apiVersion must not be null");
        code = Objects.requireNonNull(code, "code must not be null");
        message = requireText(message, "message");
        correlationId = requireText(correlationId, "correlationId");
        timestamp = TimePrecision.toMilliseconds(Objects.requireNonNull(timestamp, "timestamp must not be null"));
        violations = List.copyOf(Objects.requireNonNull(violations, "violations must not be null"));
        if (violations.size() > 100 || violations.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("violations must contain at most 100 non-null values");
        }
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
