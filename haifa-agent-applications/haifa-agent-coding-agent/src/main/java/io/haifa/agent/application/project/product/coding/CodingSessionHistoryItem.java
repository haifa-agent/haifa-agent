package io.haifa.agent.application.project.product.coding;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Bounded user-visible historical item safe for Coding product clients. */
public record CodingSessionHistoryItem(
        String id,
        Kind kind,
        String title,
        String body,
        String status,
        long sequence,
        Instant createdAt,
        Optional<String> sourceRunId,
        boolean truncated,
        Optional<String> code,
        Optional<String> diagnosticId) {
    private static final int MAXIMUM_BODY_CHARACTERS = 16_384;

    public CodingSessionHistoryItem(
            String id, Kind kind, String title, String body, String status, long sequence, Instant createdAt) {
        this(
                id,
                kind,
                title,
                body,
                status,
                sequence,
                createdAt,
                Optional.empty(),
                false,
                Optional.empty(),
                Optional.empty());
    }

    public CodingSessionHistoryItem(
            String id,
            Kind kind,
            String title,
            String body,
            String status,
            long sequence,
            Instant createdAt,
            Optional<String> sourceRunId,
            boolean truncated) {
        this(
                id,
                kind,
                title,
                body,
                status,
                sequence,
                createdAt,
                sourceRunId,
                truncated,
                Optional.empty(),
                Optional.empty());
    }

    public CodingSessionHistoryItem {
        code = Objects.requireNonNull(code).map(value -> text(value, "code", 128));
        diagnosticId = Objects.requireNonNull(diagnosticId).map(value -> text(value, "diagnosticId", 256));
        sourceRunId = Objects.requireNonNull(sourceRunId, "sourceRunId must not be null");
        sourceRunId = sourceRunId.map(value -> text(value, "sourceRunId", 256));
        id = text(id, "id", 256);
        kind = Objects.requireNonNull(kind, "kind must not be null");
        title = text(title, "title", 256);
        body = text(body, "body", MAXIMUM_BODY_CHARACTERS);
        status = text(status, "status", 64);
        if (sequence < 1) throw new IllegalArgumentException("sequence must be positive");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public enum Kind {
        USER,
        ASSISTANT,
        ERROR
    }

    private static String text(String value, String field, int maximum) {
        String checked =
                Objects.requireNonNull(value, field + " must not be null").strip();
        if (checked.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        if (checked.length() > maximum) throw new IllegalArgumentException(field + " is too long");
        return checked;
    }
}
