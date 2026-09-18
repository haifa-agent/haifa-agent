package io.haifa.agent.runtime.core.storage;

import io.haifa.agent.core.run.AgentRunId;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Committed Runtime Journal entry. The event identity never changes after append. */
public record RuntimeEvent(
        String eventId,
        AgentRunId runId,
        long sequence,
        String type,
        String eventSchemaVersion,
        Map<String, Object> data,
        Instant occurredAt,
        Optional<String> correlationId,
        Optional<String> causationId) {
    public RuntimeEvent {
        eventId = requireText(eventId, "eventId", 512);
        runId = Objects.requireNonNull(runId, "runId must not be null");
        if (sequence < 1) throw new IllegalArgumentException("event sequence must be positive");
        type = requireText(type, "type", 128);
        eventSchemaVersion = requireText(eventSchemaVersion, "eventSchemaVersion", 32);
        data = Map.copyOf(Objects.requireNonNull(data, "data must not be null"));
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        correlationId = boundedOptional(correlationId, "correlationId");
        causationId = boundedOptional(causationId, "causationId");
    }

    public RuntimeEvent(
            String eventId,
            AgentRunId runId,
            long sequence,
            String type,
            Map<String, Object> data,
            Instant occurredAt) {
        this(eventId, runId, sequence, type, "1", data, occurredAt, Optional.empty(), Optional.empty());
    }

    private static Optional<String> boundedOptional(Optional<String> value, String field) {
        return Objects.requireNonNull(value, field + " must not be null").map(item -> requireText(item, field, 256));
    }

    private static String requireText(String value, String field, int maximumLength) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " must contain 1.." + maximumLength + " characters");
        }
        return normalized;
    }
}
