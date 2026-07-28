package io.haifa.agent.application.coding.terminal.state;

import java.util.Objects;
import java.util.Optional;

public record TranscriptItem(
        String id,
        Kind kind,
        String title,
        String body,
        String status,
        boolean expanded,
        Optional<ApprovalDetails> approvalDetails) {
    public TranscriptItem {
        id = text(id, "id", 256);
        kind = Objects.requireNonNull(kind, "kind must not be null");
        title = text(title, "title", 256);
        body = bounded(body, "body", 16_384);
        status = text(status, "status", 64);
        approvalDetails = Objects.requireNonNull(approvalDetails, "approvalDetails must not be null");
        if (kind != Kind.APPROVAL && approvalDetails.isPresent()) {
            throw new IllegalArgumentException("approval details require an approval transcript item");
        }
    }

    public TranscriptItem(String id, Kind kind, String title, String body, String status, boolean expanded) {
        this(id, kind, title, body, status, expanded, Optional.empty());
    }

    public TranscriptItem append(String delta) {
        String merged = body + bounded(delta, "delta", 65_536);
        if (merged.length() > 16_384) merged = merged.substring(merged.length() - 16_384);
        return new TranscriptItem(id, kind, title, merged, status, expanded, approvalDetails);
    }

    public TranscriptItem withStatus(String value, String valueBody) {
        return new TranscriptItem(id, kind, title, valueBody, value, expanded, approvalDetails);
    }

    public TranscriptItem toggle() {
        return new TranscriptItem(id, kind, title, body, status, !expanded, approvalDetails);
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
