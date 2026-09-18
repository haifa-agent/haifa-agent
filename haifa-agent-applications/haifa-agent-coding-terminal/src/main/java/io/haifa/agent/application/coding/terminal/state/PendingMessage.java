package io.haifa.agent.application.coding.terminal.state;

import java.util.Objects;

public record PendingMessage(String id, Kind kind, String summary, long revision) {
    public PendingMessage {
        id = require(id, "id", 256);
        kind = Objects.requireNonNull(kind, "kind must not be null");
        summary = require(summary, "summary", 512);
        if (revision < 0) throw new IllegalArgumentException("revision must not be negative");
    }

    public enum Kind {
        STEER,
        FOLLOW_UP
    }

    private static String require(String value, String field, int maximum) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty() || normalized.length() > maximum) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }
}
