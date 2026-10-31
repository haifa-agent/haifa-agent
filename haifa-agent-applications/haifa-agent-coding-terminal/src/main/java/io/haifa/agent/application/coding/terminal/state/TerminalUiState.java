package io.haifa.agent.application.coding.terminal.state;

import io.haifa.agent.application.project.product.coding.CodingSessionView;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.runtime.api.RunEventCursor;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record TerminalUiState(
        String header,
        List<String> loadedResources,
        List<TranscriptItem> transcript,
        List<PendingMessage> pending,
        String status,
        String editorBuffer,
        int editorCursor,
        Optional<TerminalSelector> selector,
        TerminalFooter footer,
        int columns,
        int rows,
        Optional<CodingSessionView> session,
        Optional<AgentRunId> currentRunId,
        Optional<RunEventCursor> appliedCursor,
        Set<String> seenEventIds,
        Optional<String> recoverableError,
        TerminalActivity activity,
        boolean exitRequested,
        boolean sidebarVisible,
        boolean sidebarExplicitlyToggled,
        String themeName,
        boolean cancelArmed,
        Optional<WaitingMessage> waiting,
        List<TerminalSessionRow> sessionRows,
        int sessionRowSelected) {
    /**
     * Compatibility constructor for callers that predate the sidebar task list.
     *
     * <p>Keeps the previous argument list working so existing tests and wiring do not have to repeat the list fields;
     * the sidebar simply starts empty until the first bounded {@code session/list} poll fills it.
     */
    public TerminalUiState(
            String header,
            List<String> loadedResources,
            List<TranscriptItem> transcript,
            List<PendingMessage> pending,
            String status,
            String editorBuffer,
            int editorCursor,
            Optional<TerminalSelector> selector,
            TerminalFooter footer,
            int columns,
            int rows,
            Optional<CodingSessionView> session,
            Optional<AgentRunId> currentRunId,
            Optional<RunEventCursor> appliedCursor,
            Set<String> seenEventIds,
            Optional<String> recoverableError,
            TerminalActivity activity,
            boolean exitRequested,
            boolean sidebarVisible,
            boolean sidebarExplicitlyToggled,
            String themeName,
            boolean cancelArmed,
            Optional<WaitingMessage> waiting) {
        this(
                header,
                loadedResources,
                transcript,
                pending,
                status,
                editorBuffer,
                editorCursor,
                selector,
                footer,
                columns,
                rows,
                session,
                currentRunId,
                appliedCursor,
                seenEventIds,
                recoverableError,
                activity,
                exitRequested,
                sidebarVisible,
                sidebarExplicitlyToggled,
                themeName,
                cancelArmed,
                waiting,
                List.of(),
                0);
    }

    public TerminalUiState {
        header = Objects.requireNonNull(header, "header must not be null");
        loadedResources = List.copyOf(loadedResources);
        transcript = List.copyOf(transcript);
        pending = List.copyOf(pending);
        status = Objects.requireNonNull(status, "status must not be null");
        editorBuffer = Objects.requireNonNull(editorBuffer, "editorBuffer must not be null");
        if (editorCursor < 0 || editorCursor > editorBuffer.length()) {
            throw new IllegalArgumentException("editor cursor is out of range");
        }
        selector = Objects.requireNonNull(selector, "selector must not be null");
        footer = Objects.requireNonNull(footer, "footer must not be null");
        if (columns < 1 || rows < 1) throw new IllegalArgumentException("terminal size must be positive");
        session = Objects.requireNonNull(session, "session must not be null");
        currentRunId = Objects.requireNonNull(currentRunId, "currentRunId must not be null");
        appliedCursor = Objects.requireNonNull(appliedCursor, "appliedCursor must not be null");
        seenEventIds = Set.copyOf(seenEventIds);
        recoverableError = Objects.requireNonNull(recoverableError, "recoverableError must not be null");
        activity = Objects.requireNonNull(activity, "activity must not be null");
        themeName = (themeName == null || themeName.isBlank())
                ? "dark"
                : themeName.strip().toLowerCase(java.util.Locale.ROOT);
        waiting = Objects.requireNonNullElse(waiting, Optional.empty());
        sessionRows = List.copyOf(Objects.requireNonNullElse(sessionRows, List.of()));
        if (sessionRows.size() > MAX_SESSION_ROWS) {
            throw new IllegalArgumentException("sidebar has too many session rows");
        }
        if (sessionRowSelected < 0 || (!sessionRows.isEmpty() && sessionRowSelected >= sessionRows.size())) {
            throw new IllegalArgumentException("sidebar selection is out of range");
        }
    }

    /** Hard cap for the sidebar list; v1 truncates instead of paginating (D6). */
    public static final int MAX_SESSION_ROWS = 50;

    /** Replaces the sidebar rows and keeps the selection inside the new bounds. */
    public TerminalUiState withSessionRows(List<TerminalSessionRow> newRows) {
        List<TerminalSessionRow> bounded = List.copyOf(Objects.requireNonNull(newRows, "newRows must not be null"));
        if (bounded.size() > MAX_SESSION_ROWS) bounded = bounded.subList(0, MAX_SESSION_ROWS);
        int selected = bounded.isEmpty() ? 0 : Math.min(sessionRowSelected, bounded.size() - 1);
        AgentSessionId current = session.map(view -> view.summary().sessionId()).orElse(null);
        if (current != null) {
            for (int index = 0; index < bounded.size(); index++) {
                if (bounded.get(index).sessionId().equals(current)) {
                    selected = index;
                    break;
                }
            }
        }
        return new TerminalUiState(
                header,
                loadedResources,
                transcript,
                pending,
                status,
                editorBuffer,
                editorCursor,
                selector,
                footer,
                columns,
                rows,
                session,
                currentRunId,
                appliedCursor,
                seenEventIds,
                recoverableError,
                activity,
                exitRequested,
                sidebarVisible,
                sidebarExplicitlyToggled,
                themeName,
                cancelArmed,
                waiting,
                bounded,
                selected);
    }

    /** Moves the sidebar cursor by a bounded delta without wrapping past the ends. */
    public TerminalUiState withSessionRowMoved(int delta) {
        if (sessionRows.isEmpty()) return this;
        int next = Math.max(0, Math.min(sessionRows.size() - 1, sessionRowSelected + delta));
        if (next == sessionRowSelected) return this;
        return new TerminalUiState(
                header,
                loadedResources,
                transcript,
                pending,
                status,
                editorBuffer,
                editorCursor,
                selector,
                footer,
                columns,
                rows,
                session,
                currentRunId,
                appliedCursor,
                seenEventIds,
                recoverableError,
                activity,
                exitRequested,
                sidebarVisible,
                sidebarExplicitlyToggled,
                themeName,
                cancelArmed,
                waiting,
                sessionRows,
                next);
    }

    /** The Session under the sidebar cursor, or empty when the list has no rows. */
    public Optional<TerminalSessionRow> selectedSessionRow() {
        return sessionRows.isEmpty() ? Optional.empty() : Optional.of(sessionRows.get(sessionRowSelected));
    }

    public TerminalUiState withThemeName(String newThemeName) {
        return new TerminalUiState(
                header,
                loadedResources,
                transcript,
                pending,
                status,
                editorBuffer,
                editorCursor,
                selector,
                footer,
                columns,
                rows,
                session,
                currentRunId,
                appliedCursor,
                seenEventIds,
                recoverableError,
                activity,
                exitRequested,
                sidebarVisible,
                sidebarExplicitlyToggled,
                newThemeName,
                cancelArmed,
                waiting);
    }

    public TerminalUiState withCancelArmed(boolean newCancelArmed) {
        return new TerminalUiState(
                header,
                loadedResources,
                transcript,
                pending,
                status,
                editorBuffer,
                editorCursor,
                selector,
                footer,
                columns,
                rows,
                session,
                currentRunId,
                appliedCursor,
                seenEventIds,
                recoverableError,
                activity,
                exitRequested,
                sidebarVisible,
                sidebarExplicitlyToggled,
                themeName,
                newCancelArmed,
                waiting);
    }

    public TerminalUiState withWaiting(Optional<WaitingMessage> newWaiting) {
        return new TerminalUiState(
                header,
                loadedResources,
                transcript,
                pending,
                status,
                editorBuffer,
                editorCursor,
                selector,
                footer,
                columns,
                rows,
                session,
                currentRunId,
                appliedCursor,
                seenEventIds,
                recoverableError,
                activity,
                exitRequested,
                sidebarVisible,
                sidebarExplicitlyToggled,
                themeName,
                cancelArmed,
                newWaiting);
    }

    public TerminalUiState withStatus(String newStatus) {
        return new TerminalUiState(
                header,
                loadedResources,
                transcript,
                pending,
                newStatus,
                editorBuffer,
                editorCursor,
                selector,
                footer,
                columns,
                rows,
                session,
                currentRunId,
                appliedCursor,
                seenEventIds,
                recoverableError,
                activity,
                exitRequested,
                sidebarVisible,
                sidebarExplicitlyToggled,
                themeName,
                cancelArmed,
                waiting);
    }

    public TerminalUiState(
            String header,
            List<String> loadedResources,
            List<TranscriptItem> transcript,
            List<PendingMessage> pending,
            String status,
            String editorBuffer,
            int editorCursor,
            Optional<TerminalSelector> selector,
            TerminalFooter footer,
            int columns,
            int rows,
            Optional<CodingSessionView> session,
            Optional<AgentRunId> currentRunId,
            Optional<RunEventCursor> appliedCursor,
            Set<String> seenEventIds,
            Optional<String> recoverableError,
            TerminalActivity activity,
            boolean exitRequested,
            boolean sidebarVisible,
            boolean sidebarExplicitlyToggled,
            String themeName,
            boolean cancelArmed) {
        this(
                header,
                loadedResources,
                transcript,
                pending,
                status,
                editorBuffer,
                editorCursor,
                selector,
                footer,
                columns,
                rows,
                session,
                currentRunId,
                appliedCursor,
                seenEventIds,
                recoverableError,
                activity,
                exitRequested,
                sidebarVisible,
                sidebarExplicitlyToggled,
                themeName,
                cancelArmed,
                Optional.empty());
    }

    public TerminalUiState(
            String header,
            List<String> loadedResources,
            List<TranscriptItem> transcript,
            List<PendingMessage> pending,
            String status,
            String editorBuffer,
            int editorCursor,
            Optional<TerminalSelector> selector,
            TerminalFooter footer,
            int columns,
            int rows,
            Optional<CodingSessionView> session,
            Optional<AgentRunId> currentRunId,
            Optional<RunEventCursor> appliedCursor,
            Set<String> seenEventIds,
            Optional<String> recoverableError,
            TerminalActivity activity,
            boolean exitRequested,
            boolean sidebarVisible,
            boolean sidebarExplicitlyToggled,
            String themeName) {
        this(
                header,
                loadedResources,
                transcript,
                pending,
                status,
                editorBuffer,
                editorCursor,
                selector,
                footer,
                columns,
                rows,
                session,
                currentRunId,
                appliedCursor,
                seenEventIds,
                recoverableError,
                activity,
                exitRequested,
                sidebarVisible,
                sidebarExplicitlyToggled,
                themeName,
                false);
    }

    public TerminalUiState(
            String header,
            List<String> loadedResources,
            List<TranscriptItem> transcript,
            List<PendingMessage> pending,
            String status,
            String editorBuffer,
            int editorCursor,
            Optional<TerminalSelector> selector,
            TerminalFooter footer,
            int columns,
            int rows,
            Optional<CodingSessionView> session,
            Optional<AgentRunId> currentRunId,
            Optional<RunEventCursor> appliedCursor,
            Set<String> seenEventIds,
            Optional<String> recoverableError,
            TerminalActivity activity,
            boolean exitRequested,
            boolean sidebarVisible,
            boolean sidebarExplicitlyToggled) {
        this(
                header,
                loadedResources,
                transcript,
                pending,
                status,
                editorBuffer,
                editorCursor,
                selector,
                footer,
                columns,
                rows,
                session,
                currentRunId,
                appliedCursor,
                seenEventIds,
                recoverableError,
                activity,
                exitRequested,
                sidebarVisible,
                sidebarExplicitlyToggled,
                "dark");
    }

    public TerminalUiState(
            String header,
            List<String> loadedResources,
            List<TranscriptItem> transcript,
            List<PendingMessage> pending,
            String status,
            String editorBuffer,
            int editorCursor,
            Optional<TerminalSelector> selector,
            TerminalFooter footer,
            int columns,
            int rows,
            Optional<CodingSessionView> session,
            Optional<AgentRunId> currentRunId,
            Optional<RunEventCursor> appliedCursor,
            Set<String> seenEventIds,
            Optional<String> recoverableError,
            TerminalActivity activity,
            boolean exitRequested,
            boolean sidebarVisible) {
        this(
                header,
                loadedResources,
                transcript,
                pending,
                status,
                editorBuffer,
                editorCursor,
                selector,
                footer,
                columns,
                rows,
                session,
                currentRunId,
                appliedCursor,
                seenEventIds,
                recoverableError,
                activity,
                exitRequested,
                sidebarVisible,
                false);
    }

    public TerminalUiState(
            String header,
            List<String> loadedResources,
            List<TranscriptItem> transcript,
            List<PendingMessage> pending,
            String status,
            String editorBuffer,
            int editorCursor,
            Optional<TerminalSelector> selector,
            TerminalFooter footer,
            int columns,
            int rows,
            Optional<CodingSessionView> session,
            Optional<AgentRunId> currentRunId,
            Optional<RunEventCursor> appliedCursor,
            Set<String> seenEventIds,
            Optional<String> recoverableError,
            TerminalActivity activity,
            boolean exitRequested) {
        this(
                header,
                loadedResources,
                transcript,
                pending,
                status,
                editorBuffer,
                editorCursor,
                selector,
                footer,
                columns,
                rows,
                session,
                currentRunId,
                appliedCursor,
                seenEventIds,
                recoverableError,
                activity,
                exitRequested,
                columns >= 100,
                false);
    }

    public TerminalUiState(
            String header,
            List<String> loadedResources,
            List<TranscriptItem> transcript,
            List<PendingMessage> pending,
            String status,
            String editorBuffer,
            int editorCursor,
            Optional<TerminalSelector> selector,
            TerminalFooter footer,
            int columns,
            int rows,
            Optional<CodingSessionView> session,
            Optional<AgentRunId> currentRunId,
            Optional<RunEventCursor> appliedCursor,
            Set<String> seenEventIds,
            Optional<String> recoverableError,
            boolean exitRequested) {
        this(
                header,
                loadedResources,
                transcript,
                pending,
                status,
                editorBuffer,
                editorCursor,
                selector,
                footer,
                columns,
                rows,
                session,
                currentRunId,
                appliedCursor,
                seenEventIds,
                recoverableError,
                TerminalActivity.initial(),
                exitRequested,
                columns >= 100,
                false);
    }

    public static TerminalUiState initial(int columns, int rows) {
        return initial(columns, rows, TerminalWorkspaceContext.empty());
    }

    public static TerminalUiState initial(int columns, int rows, TerminalWorkspaceContext workspace) {
        Objects.requireNonNull(workspace, "workspace must not be null");
        return new TerminalUiState(
                "Haifa Coding Agent",
                List.of("Loaded resources: none"),
                List.of(),
                List.of(),
                "Idle",
                "",
                0,
                Optional.empty(),
                new TerminalFooter(
                        workspace.workingDirectory(),
                        workspace.gitBranch(),
                        workspace.worktree(),
                        workspace.worktreeBranch(),
                        "",
                        "context: —",
                        "",
                        "",
                        "IDLE",
                        ""),
                columns,
                rows,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Set.of(),
                Optional.empty(),
                TerminalActivity.initial(),
                false,
                columns >= 100,
                false,
                "dark",
                false,
                Optional.empty());
    }
}
