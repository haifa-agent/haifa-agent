package io.haifa.agent.application.coding.terminal.state;

import io.haifa.agent.application.project.product.coding.CodingSessionView;
import io.haifa.agent.core.run.AgentRunId;
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
        boolean exitRequested) {
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
    }

    public static TerminalUiState initial(int columns, int rows) {
        return new TerminalUiState(
                "Haifa Coding Agent",
                List.of("Loaded resources: none"),
                List.of(),
                List.of(),
                "Idle",
                "",
                0,
                Optional.empty(),
                TerminalFooter.empty(),
                columns,
                rows,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Set.of(),
                Optional.empty(),
                false);
    }
}
