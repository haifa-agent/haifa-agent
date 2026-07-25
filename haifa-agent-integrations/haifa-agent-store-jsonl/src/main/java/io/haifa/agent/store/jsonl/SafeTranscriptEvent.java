package io.haifa.agent.store.jsonl;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Safe, versioned projection envelope. It is not a Runtime persistence model. */
public record SafeTranscriptEvent(
        String schemaVersion,
        String eventId,
        String runId,
        long sequence,
        Instant occurredAt,
        String eventType,
        Map<String, Object> payload) {

    public static final String CURRENT_SCHEMA_VERSION = "1";

    public SafeTranscriptEvent {
        schemaVersion = requireText(schemaVersion, "schemaVersion");
        eventId = requireText(eventId, "eventId");
        runId = requireText(runId, "runId");
        if (sequence < 1) throw new IllegalArgumentException("sequence must be positive");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        eventType = requireText(eventType, "eventType");
        payload = Map.copyOf(Objects.requireNonNull(payload, "payload must not be null"));
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
