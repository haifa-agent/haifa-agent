package io.haifa.agent.runtime.api;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Provider-neutral client event envelope. Typed payloads are added by the event projector. */
public record AgentRunEvent(
        String eventId,
        String eventType,
        String eventSchemaVersion,
        AgentRunId runId,
        AgentSessionId sessionId,
        long sequence,
        RunEventCursor cursor,
        Instant occurredAt,
        Optional<String> correlationId,
        Optional<String> causationId,
        Payload payload) {
    public AgentRunEvent {
        eventId = InteractionOption.requireText(eventId, "eventId", 256);
        eventType = requireEventType(eventType);
        eventSchemaVersion = InteractionOption.requireText(eventSchemaVersion, "eventSchemaVersion", 32);
        runId = Objects.requireNonNull(runId, "runId must not be null");
        sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (sequence < 1) throw new IllegalArgumentException("sequence must be positive");
        cursor = Objects.requireNonNull(cursor, "cursor must not be null");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        correlationId = boundedOptional(correlationId, "correlationId");
        causationId = boundedOptional(causationId, "causationId");
        payload = Objects.requireNonNull(payload, "payload must not be null");
        if (!runId.equals(cursor.runId())
                || cursor.exclusiveSequence().isEmpty()
                || cursor.exclusiveSequence().getAsLong() != sequence) {
            throw new IllegalArgumentException("cursor must identify this event");
        }
    }

    public interface Payload {}

    private static Optional<String> boundedOptional(Optional<String> value, String field) {
        return Objects.requireNonNull(value, field + " must not be null")
                .map(item -> InteractionOption.requireText(item, field, 256));
    }

    private static String requireEventType(String value) {
        String normalized = InteractionOption.requireText(value, "eventType", 128);
        if (!normalized.matches("[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)+")) {
            throw new IllegalArgumentException("eventType must be a dotted lower-kebab token");
        }
        return normalized;
    }
}
