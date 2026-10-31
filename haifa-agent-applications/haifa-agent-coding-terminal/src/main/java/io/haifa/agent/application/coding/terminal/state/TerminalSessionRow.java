package io.haifa.agent.application.coding.terminal.state;

import io.haifa.agent.application.project.product.coding.CodingSessionSummary;
import io.haifa.agent.core.session.AgentSessionId;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One Session row of the sidebar task list.
 *
 * <p>A row is a Session, never a single Run: one Session can own several successive Runs and at most one active Run.
 * The row carries only what the list needs to render and to be hit-tested, so the View stays free of product types.
 * The status label is derived from the authoritative summary mapping rather than from local UI guesses.
 */
public record TerminalSessionRow(
        AgentSessionId sessionId,
        String displayName,
        String statusLabel,
        boolean active,
        int queuedCount,
        java.time.Instant lastActivityAt) {
    /** Upper bound of the rendered name; the sidebar clips to its column width as well. */
    private static final int MAX_NAME = 120;

    public TerminalSessionRow {
        sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        displayName = Objects.requireNonNull(displayName, "displayName must not be null")
                .strip();
        if (displayName.isEmpty()) displayName = "(unnamed session)";
        if (displayName.length() > MAX_NAME) displayName = displayName.substring(0, MAX_NAME);
        statusLabel = Objects.requireNonNull(statusLabel, "statusLabel must not be null");
        if (queuedCount < 0) throw new IllegalArgumentException("queuedCount must not be negative");
        lastActivityAt = Objects.requireNonNull(lastActivityAt, "lastActivityAt must not be null");
    }

    /**
     * Builds a row from the authoritative summary.
     *
     * <p>{@code active} means the Session has an active Run right now. It is derived from the summary's own active Run
     * pair instead of a TUI-side flag, so a Session that settles while the list is stale still renders as history once
     * the next bounded poll refreshes it.
     */
    public static TerminalSessionRow from(CodingSessionSummary summary) {
        Objects.requireNonNull(summary, "summary must not be null");
        return new TerminalSessionRow(
                summary.sessionId(),
                summary.displayName(),
                statusLabel(summary),
                summary.activeRunId().isPresent(),
                summary.queuedCount(),
                summary.lastActivityAt());
    }

    /**
     * Maps the authoritative state to a short label.
     *
     * <p>The active Run status wins while a Run is in flight because it distinguishes a working Run from one that waits
     * on a human. Otherwise the Session status is used, which covers the idle and terminal cases.
     */
    private static String statusLabel(CodingSessionSummary summary) {
        String label = summary.activeRunStatus()
                .map(status -> switch (status.name()) {
                    case "RUNNING" -> "RUNNING";
                    case "WAITING_APPROVAL" -> "WAITING APPROVAL";
                    case "WAITING_INTERACTION" -> "WAITING INPUT";
                    case "QUEUED" -> "QUEUED";
                    default -> status.name();
                })
                .orElseGet(() -> summary.status().name());
        return label.toUpperCase(Locale.ROOT);
    }

    /** True when the Session waits on a human, which the sidebar orders and highlights first. */
    public boolean awaitingHuman() {
        return statusLabel.startsWith("WAITING");
    }

    /** Renders the single sidebar line for this row, including the queue hint and the selection marker. */
    public String render(boolean selected) {
        StringBuilder text = new StringBuilder();
        text.append(selected ? "\u25B8 " : "  ");
        text.append(displayName);
        text.append(" \u00B7 ").append(statusLabel);
        if (queuedCount > 0) text.append(" +").append(queuedCount);
        return text.toString();
    }

    /**
     * Orders sessions for the sidebar: human-waiting first, then live work, then recent history.
     *
     * <p>A Run that waits on a human is the only row that blocks progress, so burying it under active or historical
     * Sessions would hide the one action the operator has to take. Ties fall back to the most recent activity, which
     * keeps the list stable without inventing a priority that the Host does not publish. The cap is applied after
     * ordering so a truncated list keeps the most actionable rows.
     */
    public static List<TerminalSessionRow> ordered(List<CodingSessionSummary> summaries) {
        Objects.requireNonNull(summaries, "summaries must not be null");
        return summaries.stream()
                .map(TerminalSessionRow::from)
                .sorted(java.util.Comparator.comparingInt(TerminalSessionRow::rank)
                        .thenComparing(TerminalSessionRow::lastActivityAt, java.util.Comparator.reverseOrder()))
                .limit(MAX_ROWS)
                .toList();
    }

    /** Lower rank sorts first; the values only encode the required order. */
    private static int rank(TerminalSessionRow row) {
        if (row.awaitingHuman()) return 0;
        if (row.active()) return 1;
        return 2;
    }

    /** Row cap shared with the UI state so the controller cannot request more than the sidebar renders. */
    private static final int MAX_ROWS = 50;
}
