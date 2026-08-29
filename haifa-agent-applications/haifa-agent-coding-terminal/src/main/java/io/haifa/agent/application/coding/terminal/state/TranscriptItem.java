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
        Optional<ApprovalDetails> approvalDetails,
        Optional<Long> startedAtEpochMillis,
        Optional<Long> durationMillis) {
    public TranscriptItem {
        id = text(id, "id", 256);
        kind = Objects.requireNonNull(kind, "kind must not be null");
        title = text(title, "title", 256);
        body = bounded(body, "body", 16_384);
        status = text(status, "status", 64);
        approvalDetails = Objects.requireNonNull(approvalDetails, "approvalDetails must not be null");
        startedAtEpochMillis = Objects.requireNonNull(startedAtEpochMillis, "startedAtEpochMillis must not be null");
        durationMillis = Objects.requireNonNull(durationMillis, "durationMillis must not be null");
        if (startedAtEpochMillis.isPresent() && startedAtEpochMillis.orElseThrow() < 0) {
            throw new IllegalArgumentException("startedAtEpochMillis must not be negative");
        }
        if (durationMillis.isPresent() && durationMillis.orElseThrow() < 0) {
            throw new IllegalArgumentException("durationMillis must not be negative");
        }
        if (kind != Kind.APPROVAL && approvalDetails.isPresent()) {
            throw new IllegalArgumentException("approval details require an approval transcript item");
        }
    }

    public TranscriptItem(String id, Kind kind, String title, String body, String status, boolean expanded) {
        this(id, kind, title, body, status, expanded, Optional.empty());
    }

    public TranscriptItem(
            String id,
            Kind kind,
            String title,
            String body,
            String status,
            boolean expanded,
            Optional<ApprovalDetails> approvalDetails) {
        this(id, kind, title, body, status, expanded, approvalDetails, Optional.empty(), Optional.empty());
    }

    public TranscriptItem append(String delta) {
        String merged = body + bounded(delta, "delta", 65_536);
        if (merged.length() > 16_384) merged = merged.substring(merged.length() - 16_384);
        return new TranscriptItem(
                id, kind, title, merged, status, expanded, approvalDetails, startedAtEpochMillis, durationMillis);
    }

    public TranscriptItem withStatus(String value, String valueBody) {
        return new TranscriptItem(
                id, kind, title, valueBody, value, expanded, approvalDetails, startedAtEpochMillis, durationMillis);
    }

    public TranscriptItem toggle() {
        return new TranscriptItem(
                id, kind, title, body, status, !expanded, approvalDetails, startedAtEpochMillis, durationMillis);
    }

    /**
     * Whether the item renders as a single collapsed line until the user expands it. Approval, authentication
     * and history resource items stay visible because their bodies carry information the user must see.
     */
    public boolean collapsible() {
        return !expanded && toggleable();
    }

    /** Whether ctrl+o expansion toggling applies to the item, independent of its current expanded state. */
    public boolean toggleable() {
        return switch (kind) {
            case TOOL, EXECUTION, SUMMARY -> true;
            case RESOURCE -> id.startsWith("delivery-") || id.startsWith("resource-");
            default -> false;
        };
    }

    @Override
    public String toString() {
        return "TranscriptItem[id=" + id + ", kind=" + kind + ", title=" + title
                + ", body=[REDACTED], status=" + status + ", expanded=" + expanded
                + ", approvalDetails=[REDACTED]]";
    }

    public enum Kind {
        USER,
        ASSISTANT,
        TOOL,
        EXECUTION,
        APPROVAL,
        RESOURCE,
        SUMMARY,
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
