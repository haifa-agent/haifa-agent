package io.haifa.agent.application.coding.terminal.state;

import java.util.Objects;

public record TranscriptItem(String id, Kind kind, String title, String body, String status, boolean expanded) {
    public TranscriptItem {
        id = text(id, "id", 256);
        kind = Objects.requireNonNull(kind, "kind must not be null");
        title = text(title, "title", 256);
        body = bounded(body, "body", 16_384);
        status = text(status, "status", 64);
    }

    public TranscriptItem append(String delta) {
        String merged = body + bounded(delta, "delta", 65_536);
        if (merged.length() > 16_384) merged = merged.substring(merged.length() - 16_384);
        return new TranscriptItem(id, kind, title, merged, status, expanded);
    }

    public TranscriptItem withStatus(String value, String valueBody) {
        return new TranscriptItem(id, kind, title, valueBody, value, expanded);
    }

    public TranscriptItem toggle() {
        return new TranscriptItem(id, kind, title, body, status, !expanded);
    }

    public enum Kind {
        USER,
        ASSISTANT,
        TOOL,
        EXECUTION,
        APPROVAL,
        RESOURCE,
        ERROR
    }

    private static String text(String value, String field, int maximum) {
        String normalized = bounded(value, field, maximum).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }

    private static String bounded(String value, String field, int maximum) {
        String checked = Objects.requireNonNull(value, field + " must not be null");
        if (checked.length() > maximum) throw new IllegalArgumentException(field + " is too long");
        return checked;
    }
}
