package io.haifa.agent.application.project.product.coding;

import java.time.Instant;
import java.util.Objects;

/** Bounded user-visible historical item safe for Coding product clients. */
public record CodingSessionHistoryItem(
        String id, Kind kind, String title, String body, String status, long sequence, Instant createdAt) {
    private static final int MAXIMUM_BODY_CHARACTERS = 16_384;

    public CodingSessionHistoryItem {
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
