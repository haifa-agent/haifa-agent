package io.haifa.agent.runtime.core.storage;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record AppliedCommandResult(
        String callerScope,
        String operation,
        String idempotencyKey,
        Optional<String> requestDigest,
        int resultVersion,
        String resultPayload,
        Instant appliedAt) {

    public AppliedCommandResult {
        callerScope = requireText(callerScope, "callerScope");
        operation = requireText(operation, "operation");
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        requestDigest = Objects.requireNonNull(requestDigest, "requestDigest must not be null")
                .map(value -> requireText(value, "requestDigest"));
        if (resultVersion < 1) throw new IllegalArgumentException("resultVersion must be positive");
        resultPayload = Objects.requireNonNull(resultPayload, "resultPayload must not be null");
        appliedAt = Objects.requireNonNull(appliedAt, "appliedAt must not be null");
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
