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
        Optional<Long> durationMillis,
        Optional<Integer> exitCode,
        Optional<String> toolName,
        Optional<ToolDetails> toolDetails,
        Optional<String> sourceRunId) {
    public TranscriptItem {
        id = text(id, "id", 256);
        kind = Objects.requireNonNull(kind, "kind must not be null");
        title = text(title, "title", 256);
        body = bounded(body, "body", 16_384);
        status = text(status, "status", 64);
        approvalDetails = Objects.requireNonNull(approvalDetails, "approvalDetails must not be null");
        startedAtEpochMillis = Objects.requireNonNull(startedAtEpochMillis, "startedAtEpochMillis must not be null");
        durationMillis = Objects.requireNonNull(durationMillis, "durationMillis must not be null");
        exitCode = Objects.requireNonNull(exitCode, "exitCode must not be null");
        toolName = Objects.requireNonNull(toolName, "toolName must not be null");
        toolDetails = Objects.requireNonNull(toolDetails, "toolDetails must not be null");
        sourceRunId = Objects.requireNonNull(sourceRunId, "sourceRunId must not be null");
        if (startedAtEpochMillis.isPresent() && startedAtEpochMillis.orElseThrow() < 0) {
            throw new IllegalArgumentException("startedAtEpochMillis must not be negative");
        }
        if (durationMillis.isPresent() && durationMillis.orElseThrow() < 0) {
            throw new IllegalArgumentException("durationMillis must not be negative");
        }
        if (kind != Kind.APPROVAL && kind != Kind.CLARIFICATION && approvalDetails.isPresent()) {
            throw new IllegalArgumentException("approval details require an approval transcript item");
        }
        toolName.ifPresent(value -> text(value, "toolName", 128));
        sourceRunId.ifPresent(value -> text(value, "sourceRunId", 256));
    }

    public TranscriptItem(
            String id,
            Kind kind,
            String title,
            String body,
            String status,
            boolean expanded,
            Optional<ApprovalDetails> approvalDetails,
            Optional<Long> startedAtEpochMillis,
            Optional<Long> durationMillis,
            Optional<Integer> exitCode,
            Optional<String> toolName,
            Optional<ToolDetails> toolDetails) {
        this(
                id,
                kind,
                title,
                body,
                status,
                expanded,
                approvalDetails,
                startedAtEpochMillis,
                durationMillis,
                exitCode,
                toolName,
                toolDetails,
                Optional.empty());
    }

    public TranscriptItem(
            String id,
            Kind kind,
            String title,
            String body,
            String status,
            boolean expanded,
            Optional<ApprovalDetails> approvalDetails,
            Optional<Long> startedAtEpochMillis,
            Optional<Long> durationMillis,
            Optional<Integer> exitCode,
            Optional<String> toolName) {
        this(
                id,
                kind,
                title,
                body,
                status,
                expanded,
                approvalDetails,
                startedAtEpochMillis,
                durationMillis,
                exitCode,
                toolName,
                Optional.empty());
    }

    public TranscriptItem(
            String id,
            Kind kind,
            String title,
            String body,
            String status,
            boolean expanded,
            Optional<ApprovalDetails> approvalDetails,
            Optional<Long> startedAtEpochMillis,
            Optional<Long> durationMillis,
            Optional<Integer> exitCode) {
        this(
                id,
                kind,
                title,
                body,
                status,
                expanded,
                approvalDetails,
                startedAtEpochMillis,
                durationMillis,
                exitCode,
                Optional.empty());
    }

    public TranscriptItem(
            String id,
            Kind kind,
            String title,
            String body,
            String status,
            boolean expanded,
            Optional<ApprovalDetails> approvalDetails,
            Optional<Long> startedAtEpochMillis,
            Optional<Long> durationMillis) {
        this(
                id,
                kind,
                title,
                body,
                status,
                expanded,
                approvalDetails,
                startedAtEpochMillis,
                durationMillis,
                Optional.empty(),
                Optional.empty());
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
                id,
                kind,
                title,
                merged,
                status,
                expanded,
                approvalDetails,
                startedAtEpochMillis,
                durationMillis,
                exitCode,
                toolName,
                toolDetails,
                sourceRunId);
    }

    public TranscriptItem withStatus(String value, String valueBody) {
        return new TranscriptItem(
                id,
                kind,
                title,
                valueBody,
                value,
                expanded,
                approvalDetails,
                startedAtEpochMillis,
                durationMillis,
                exitCode,
                toolName,
                toolDetails,
                sourceRunId);
    }

    public TranscriptItem toggle() {
        return new TranscriptItem(
                id,
                kind,
                title,
                body,
                status,
                !expanded,
                approvalDetails,
                startedAtEpochMillis,
                durationMillis,
                exitCode,
                toolName,
                toolDetails,
                sourceRunId);
    }

    public TranscriptItem withToolDetails(ToolDetails value) {
        return new TranscriptItem(
                id,
                kind,
                title,
                body,
                status,
                expanded,
                approvalDetails,
                startedAtEpochMillis,
                durationMillis,
                exitCode,
                toolName,
                Optional.of(value),
                sourceRunId);
    }

    public TranscriptItem withSourceRunId(String runId) {
        return new TranscriptItem(
                id,
                kind,
                title,
                body,
                status,
                expanded,
                approvalDetails,
                startedAtEpochMillis,
                durationMillis,
                exitCode,
                toolName,
                toolDetails,
                Optional.of(runId));
    }

    public TranscriptItem toggleTechnicalDetails() {
        return toolDetails.map(value -> withToolDetails(value.toggle())).orElse(this);
    }

    /**
     * Whether the item renders as a single collapsed line until the user expands it. Approval, authentication
     * and history resource items stay visible because their bodies carry information the user must see.
     */
    public boolean collapsible() {
        return !expanded && kind != Kind.APPROVAL && kind != Kind.CLARIFICATION && toggleable();
    }

    /** Whether ctrl+o expansion toggling applies to the item, independent of its current expanded state. */
    public boolean toggleable() {
        return switch (kind) {
            case TOOL, EXECUTION, SUMMARY, APPROVAL -> true;
            case RESOURCE -> id.startsWith("delivery-") || id.startsWith("resource-");
            default -> false;
        };
    }

    @Override
    public String toString() {
        return "TranscriptItem[id=" + id + ", kind=" + kind + ", title=" + title
                + ", body=[REDACTED], status=" + status + ", expanded=" + expanded
                + ", approvalDetails=[REDACTED], toolName=" + toolName.orElse("") + "]";
    }

    public enum Kind {
        USER,
        ASSISTANT,
        TOOL,
        EXECUTION,
        APPROVAL,
        CLARIFICATION,
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
