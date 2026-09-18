package io.haifa.agent.contract.event;

import io.haifa.agent.contract.common.ApiVersion;
import io.haifa.agent.contract.common.CorrelationId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record RunEventEnvelope<T extends RunEventPayload>(
        ApiVersion apiVersion,
        String eventId,
        String eventType,
        String eventSchemaVersion,
        String runId,
        String sessionId,
        long sequence,
        RunEventCursor cursor,
        Instant occurredAt,
        Optional<CorrelationId> correlationId,
        Optional<String> causationId,
        T payload) {
    public RunEventEnvelope {
        apiVersion = Objects.requireNonNull(apiVersion, "apiVersion must not be null");
        eventId = text(eventId, "eventId", 256);
        eventType = text(eventType, "eventType", 128);
        eventSchemaVersion = text(eventSchemaVersion, "eventSchemaVersion", 32);
        runId = text(runId, "runId", 256);
        sessionId = text(sessionId, "sessionId", 256);
        if (sequence < 1) throw new IllegalArgumentException("sequence must be positive");
        cursor = Objects.requireNonNull(cursor, "cursor must not be null");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        correlationId = Objects.requireNonNull(correlationId, "correlationId must not be null");
        causationId = Objects.requireNonNull(causationId, "causationId must not be null")
                .map(value -> text(value, "causationId", 256));
        payload = Objects.requireNonNull(payload, "payload must not be null");
    }

    private static String text(String value, String field, int maximumLength) {
        return CorrelationId.requireText(value, field, maximumLength);
    }
}
