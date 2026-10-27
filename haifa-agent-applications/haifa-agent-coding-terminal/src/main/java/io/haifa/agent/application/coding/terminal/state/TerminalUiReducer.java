package io.haifa.agent.application.coding.terminal.state;

import io.haifa.agent.application.coding.terminal.event.TerminalUiAction;
import io.haifa.agent.application.project.product.coding.CodingModelState;
import io.haifa.agent.application.project.product.coding.CodingSessionView;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationProgressView;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.execution.api.ExecutionOutputChannel;
import io.haifa.agent.execution.api.ToolOutputPreview;
import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.runtime.api.AgentRunOutputEvent;
import io.haifa.agent.runtime.api.AgentRunOutputEventType;
import io.haifa.agent.runtime.api.RunEventPayloads;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Deterministic, side-effect-free projection of product and committed Runtime facts. */
public final class TerminalUiReducer {
    private static final int MAX_TRANSCRIPT_TITLE_LENGTH = 256;
    private static final int MAX_TOOL_TARGET_LENGTH = 256;
    private static final String OUTCOME_UNKNOWN_STATUS = "OUTCOME_UNKNOWN";
    private static final String TOOL_OUTCOME_UNKNOWN_CODE = "TOOL_OUTCOME_UNKNOWN";
    private static final String OUTCOME_UNKNOWN_NEXT_ACTION =
            "Inspect authoritative local or remote state before deciding whether another command is safe.";
    private static final Set<String> TERMINAL_RUN_STATUSES = Set.of("COMPLETED", "FAILED", "CANCELLED", "TIMEOUT");
    private static final Set<String> AUTHENTICATION_PROGRESS_STATUSES =
            Set.of("STARTING", "WAITING_USER", "EXCHANGING", "STORING");

    public TerminalUiState reduce(TerminalUiState state, TerminalUiAction action) {
        if (action instanceof TerminalUiAction.SessionLoaded loaded) {
            var view = loaded.view();
            var active = view.activeRun().map(value -> value.runId());
            boolean sameSession = state.session()
                    .map(current ->
                            current.summary().sessionId().equals(view.summary().sessionId()))
                    .orElse(false);
            boolean sameRun = sameSession && state.currentRunId().equals(active);
            List<TranscriptItem> transcript = sameSession ? state.transcript() : List.of();
            java.util.Set<String> seen = sameRun ? state.seenEventIds() : java.util.Set.of();
            Optional<io.haifa.agent.runtime.api.RunEventCursor> cursor = sameRun
                    ? state.appliedCursor()
                    : active.map(io.haifa.agent.runtime.api.RunEventCursor::beforeFirst);
            var footer = new TerminalFooter(
                    state.footer().project(),
                    state.footer().gitBranch(),
                    state.footer().worktree(),
                    state.footer().worktreeBranch(),
                    active.isPresent()
                            ? "task: " + view.activeRunTaskSummary().orElse("description unavailable")
                            : "session: " + view.summary().displayName(),
                    "queue: " + view.summary().queuedCount(),
                    view.model().model().providerDisplayName(),
                    modelFooterSummary(view),
                    view.summary().activeRunStatus().map(Enum::name).orElse("IDLE"),
                    "");
            return copy(
                    state,
                    List.copyOf(loaded.resources()),
                    transcript,
                    sameRun ? state.pending() : List.of(),
                    active.isPresent() ? "THINKING" : "Idle",
                    state.editorBuffer(),
                    state.editorCursor(),
                    selectorAfterSessionLoad(state.selector(), view),
                    footer,
                    state.columns(),
                    state.rows(),
                    Optional.of(view),
                    active,
                    cursor,
                    seen,
                    Optional.empty(),
                    state.exitRequested());
        }
        if (action instanceof TerminalUiAction.SessionCleared cleared) {
            return copy(
                    state,
                    List.of("Loaded resources: none"),
                    List.of(),
                    List.of(),
                    cleared.status(),
                    "",
                    0,
                    Optional.empty(),
                    new TerminalFooter(
                            state.footer().project(),
                            state.footer().gitBranch(),
                            state.footer().worktree(),
                            state.footer().worktreeBranch(),
                            "",
                            "context: —",
                            "",
                            "",
                            "IDLE",
                            ""),
                    state.columns(),
                    state.rows(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    java.util.Set.of(),
                    Optional.empty(),
                    state.exitRequested());
        }
        if (action instanceof TerminalUiAction.HistoryLoaded loaded) {
            if (state.session()
                    .map(value -> value.summary().sessionId())
                    .filter(loaded.history().sessionId()::equals)
                    .isEmpty()) {
                return state;
            }
            List<TranscriptItem> transcript = new ArrayList<>();
            if (loaded.history().earlierHistoryAvailable()) {
                transcript.add(new TranscriptItem(
                        "history-earlier",
                        TranscriptItem.Kind.RESOURCE,
                        "Earlier history",
                        "Earlier history is not loaded or has been compacted.",
                        "RESTORED",
                        false));
            }
            loaded.history().items().stream()
                    .map(TerminalUiReducer::historyItem)
                    .forEach(transcript::add);
            return copy(
                    state,
                    state.loadedResources(),
                    transcript,
                    state.pending(),
                    state.status(),
                    state.editorBuffer(),
                    state.editorCursor(),
                    state.selector(),
                    state.footer(),
                    state.columns(),
                    state.rows(),
                    state.session(),
                    state.currentRunId(),
                    state.appliedCursor(),
                    state.seenEventIds(),
                    state.recoverableError(),
                    state.exitRequested());
        }
        if (action instanceof TerminalUiAction.ResourcesChanged resources) {
            return copy(
                    state,
                    List.copyOf(resources.resources()),
                    state.transcript(),
                    state.pending(),
                    state.status(),
                    state.editorBuffer(),
                    state.editorCursor(),
                    state.selector(),
                    state.footer(),
                    state.columns(),
                    state.rows(),
                    state.session(),
                    state.currentRunId(),
                    state.appliedCursor(),
                    state.seenEventIds(),
                    state.recoverableError(),
                    state.exitRequested());
        }
        if (action instanceof TerminalUiAction.ContextChanged context) {
            TerminalFooter footer = state.footer();
            return copy(
                    state,
                    state.loadedResources(),
                    state.transcript(),
                    state.pending(),
                    state.status(),
                    state.editorBuffer(),
                    state.editorCursor(),
                    state.selector(),
                    new TerminalFooter(
                            footer.project(),
                            footer.gitBranch(),
                            footer.worktree(),
                            footer.worktreeBranch(),
                            footer.session(),
                            context.indicator(),
                            footer.provider(),
                            footer.model(),
                            footer.runStatus(),
                            footer.sandbox()),
                    state.columns(),
                    state.rows(),
                    state.session(),
                    state.currentRunId(),
                    state.appliedCursor(),
                    state.seenEventIds(),
                    state.recoverableError(),
                    state.exitRequested());
        }
        if (action instanceof TerminalUiAction.ShellCompleted shell) {
            List<TranscriptItem> items = new ArrayList<>(state.transcript());
            items.add(new TranscriptItem(
                    "shell-" + items.size(),
                    TranscriptItem.Kind.EXECUTION,
                    shell.command(),
                    shell.summary(),
                    shell.processState(),
                    true));
            return copyWithTranscript(state, List.copyOf(items));
        }
        if (action instanceof TerminalUiAction.ExportCompleted exported) {
            List<TranscriptItem> items = new ArrayList<>(state.transcript());
            items.add(new TranscriptItem(
                    "export-" + items.size(),
                    TranscriptItem.Kind.RESOURCE,
                    "Session export",
                    exported.logicalPath() + " · " + exported.messageCount() + " messages",
                    "EXPORTED",
                    false));
            return copyWithTranscript(state, List.copyOf(items));
        }
        if (action instanceof TerminalUiAction.DeviceLoginInstructionsPresented instructions) {
            TerminalSelector selector = new TerminalSelector(
                    "auth-device",
                    "Device code " + instructions.userCode(),
                    List.of("Copy device code", "Copy browser link", "Cancel sign-in"),
                    0,
                    List.of(
                            new TerminalSelectorDetail(
                                    "Paste it in the browser to finish authorization", "", "Device code sign-in"),
                            new TerminalSelectorDetail(instructions.verificationUri(), "", "Device code sign-in"),
                            new TerminalSelectorDetail("Exit this sign-in", "", "Device code sign-in")));
            return copyWithStatus(
                    copyWithSelector(state, Optional.of(selector)),
                    "Enter the device code in the browser · Esc to cancel");
        }
        if (action instanceof TerminalUiAction.BrowserLoginInstructionsPresented instructions) {
            return copyWithStatus(
                    copyWithSelector(
                            state,
                            Optional.of(new TerminalSelector(
                                    "auth-wait",
                                    "Waiting for " + instructions.connectionName() + " authorization",
                                    List.of("Copy authorization link", "Reopen browser", "Cancel sign-in"),
                                    0))),
                    "Waiting for browser authorization · Esc to cancel");
        }
        if (action instanceof TerminalUiAction.AuthenticationProgressed progress) {
            List<TranscriptItem> items = new ArrayList<>(state.transcript());
            upsert(
                    items,
                    new TranscriptItem(
                            "auth-external-progress",
                            TranscriptItem.Kind.RESOURCE,
                            progress.connectionName() + " connection",
                            authenticationProgressBody(progress.phase()),
                            progress.phase().name(),
                            true));
            return copyWithStatus(
                    copyWithTranscript(state, List.copyOf(items)), authenticationProgressStatus(progress.phase()));
        }
        if (action instanceof TerminalUiAction.AuthenticationCompleted completed) {
            List<TranscriptItem> items = new ArrayList<>(state.transcript());
            String compatibility = completed.unofficialLocalCompatibility() ? "\nMode: UNOFFICIAL_LOCAL_COMPAT" : "";
            upsert(
                    items,
                    new TranscriptItem(
                            "auth-external-progress",
                            TranscriptItem.Kind.RESOURCE,
                            completed.connectionName() + " connection",
                            "Connected. Credentials were saved to the system credential store." + compatibility,
                            "CONNECTED",
                            false));
            return copyWithTranscript(state, List.copyOf(items));
        }
        if (action instanceof TerminalUiAction.AuthenticationFailed failure) {
            List<TranscriptItem> items = new ArrayList<>(state.transcript());
            String lastStage = items.stream()
                    .filter(item -> item.id().equals("auth-external-progress"))
                    .filter(item -> AUTHENTICATION_PROGRESS_STATUSES.contains(item.status()))
                    .findFirst()
                    .map(TranscriptItem::body)
                    .orElse("");
            upsert(
                    items,
                    new TranscriptItem(
                            "auth-external-progress",
                            TranscriptItem.Kind.ERROR,
                            failure.connectionName() + " connection failed",
                            authenticationFailureBody(failure.code(), lastStage),
                            "FAILED",
                            true));
            return reduce(
                    copyWithTranscript(state, List.copyOf(items)),
                    new TerminalUiAction.RecoverableFailure(failure.code()));
        }
        if (action instanceof TerminalUiAction.InteractionPresented presented) {
            var interaction = presented.interaction();
            Optional<ClarificationDetails> clarification = ClarificationDetails.from(interaction);
            ApprovalDetails details = clarification
                    .map(ClarificationDetails::toApprovalDetails)
                    .orElseGet(() -> ApprovalDetails.from(interaction, presented.allowForSession()));
            List<TranscriptItem> items = new ArrayList<>(state.transcript());
            String id = "interaction-" + interaction.requestId().value();
            int existing = index(items, id);
            boolean expanded = existing >= 0 && items.get(existing).expanded();
            upsert(
                    items,
                    new TranscriptItem(
                            id,
                            clarification.isPresent()
                                    ? TranscriptItem.Kind.CLARIFICATION
                                    : TranscriptItem.Kind.APPROVAL,
                            (clarification.isPresent() ? "Clarification · " : "Approval · ") + details.title(),
                            details.content(),
                            interaction.state().name(),
                            expanded,
                            Optional.of(details)));
            return copyWithTranscript(state, List.copyOf(items));
        }
        if (action instanceof TerminalUiAction.InteractionReceiptReceived received) {
            String id = "interaction-" + received.receipt().requestId().value();
            List<TranscriptItem> items = new ArrayList<>(state.transcript());
            int index = index(items, id);
            if (index >= 0) {
                TranscriptItem current = items.get(index);
                items.set(
                        index,
                        current.withStatus(received.receipt().interactionState().name(), current.body()));
            }
            return copyWithTranscript(state, List.copyOf(items));
        }
        if (action instanceof TerminalUiAction.RunEventReceived received) {
            return event(state, received.event());
        }
        if (action instanceof TerminalUiAction.RunOutputReceived received) {
            return output(state, received.event());
        }
        if (action instanceof TerminalUiAction.ToolOutputPreviewReceived received) {
            var preview = received.preview();
            if (state.currentRunId().filter(preview.runId()::equals).isEmpty()) return state;
            String id = "tool-" + preview.toolCallId().value();
            int existing = index(state.transcript(), id);
            if (existing < 0 || (preview.text().isEmpty() && !preview.outputTruncated() && !preview.previewDropped()))
                return state;
            List<TranscriptItem> items = new ArrayList<>(state.transcript());
            TranscriptItem current = items.get(existing);
            if (terminalToolStatus(current.status())) return state;
            items.set(existing, appendToolPreview(current, preview));
            return copyWithTranscript(state, List.copyOf(items));
        }
        if (action instanceof TerminalUiAction.UserMessageCommitted committed) {
            var items = new ArrayList<>(state.transcript());
            items.add(new TranscriptItem(
                    committed.id(), TranscriptItem.Kind.USER, "You", committed.text(), "SENT", true));
            return copy(
                    state,
                    state.loadedResources(),
                    items,
                    state.pending(),
                    state.status(),
                    "",
                    0,
                    state.selector(),
                    state.footer(),
                    state.columns(),
                    state.rows(),
                    state.session(),
                    state.currentRunId(),
                    state.appliedCursor(),
                    state.seenEventIds(),
                    state.recoverableError(),
                    state.exitRequested());
        }
        if (action instanceof TerminalUiAction.UserMessageRejected rejected) {
            return copyWithTranscript(
                    state,
                    state.transcript().stream()
                            .filter(item -> !item.id().equals(rejected.id()))
                            .toList());
        }
        if (action instanceof TerminalUiAction.EditorChanged editor) {
            return copy(
                    state,
                    state.loadedResources(),
                    state.transcript(),
                    state.pending(),
                    state.status(),
                    editor.buffer(),
                    editor.cursor(),
                    state.selector(),
                    state.footer(),
                    state.columns(),
                    state.rows(),
                    state.session(),
                    state.currentRunId(),
                    state.appliedCursor(),
                    state.seenEventIds(),
                    state.recoverableError(),
                    state.exitRequested());
        }
        if (action instanceof TerminalUiAction.PendingChanged pending) {
            List<PendingMessage> merged = new ArrayList<>();
            state.pending().stream()
                    .filter(value -> value.kind() == PendingMessage.Kind.STEER)
                    .forEach(merged::add);
            pending.messages().stream()
                    .filter(value -> pendingIndex(merged, value.id()) < 0)
                    .forEach(merged::add);
            return copyWithPending(state, List.copyOf(merged));
        }
        if (action instanceof TerminalUiAction.StatusChanged status) {
            return copyWithStatus(state, status.status());
        }
        if (action instanceof TerminalUiAction.SelectorOpened selector) {
            return copyWithSelector(state, Optional.of(selector.selector()));
        }
        if (action instanceof TerminalUiAction.SelectorMoved moved) {
            return copyWithSelector(state, state.selector().map(value -> value.move(moved.delta())));
        }
        if (action instanceof TerminalUiAction.SelectorClosed) {
            return copyWithSelector(state, Optional.empty());
        }
        if (action instanceof TerminalUiAction.ToggleExpanded toggle) {
            List<TranscriptItem> items = state.transcript().stream()
                    .map(value -> value.id().equals(toggle.itemId()) ? value.toggle() : value)
                    .toList();
            return copyWithTranscript(state, items);
        }
        if (action instanceof TerminalUiAction.ToggleTechnicalDetails toggle) {
            return copyWithTranscript(
                    state,
                    state.transcript().stream()
                            .map(value -> value.id().equals(toggle.itemId()) ? value.toggleTechnicalDetails() : value)
                            .toList());
        }
        if (action instanceof TerminalUiAction.TerminalResized resized) {
            int newCols = Math.max(1, resized.columns());
            int newRows = Math.max(1, resized.rows());
            boolean sidebarVisible = state.sidebarExplicitlyToggled() ? state.sidebarVisible() : newCols >= 100;
            return copy(
                    state,
                    state.loadedResources(),
                    state.transcript(),
                    state.pending(),
                    state.status(),
                    state.editorBuffer(),
                    state.editorCursor(),
                    state.selector(),
                    state.footer(),
                    newCols,
                    newRows,
                    state.session(),
                    state.currentRunId(),
                    state.appliedCursor(),
                    state.seenEventIds(),
                    state.recoverableError(),
                    state.activity(),
                    state.exitRequested(),
                    sidebarVisible,
                    state.sidebarExplicitlyToggled());
        }
        if (action instanceof TerminalUiAction.RecoverableFailure failure) {
            return copy(
                    state,
                    state.loadedResources(),
                    state.transcript(),
                    state.pending(),
                    "Recovery required",
                    state.editorBuffer(),
                    state.editorCursor(),
                    state.selector(),
                    state.footer(),
                    state.columns(),
                    state.rows(),
                    state.session(),
                    state.currentRunId(),
                    state.appliedCursor(),
                    state.seenEventIds(),
                    Optional.of(failure.code()),
                    state.exitRequested());
        }
        if (action instanceof TerminalUiAction.ExitRequested) {
            return copy(
                    state,
                    state.loadedResources(),
                    state.transcript(),
                    state.pending(),
                    state.status(),
                    state.editorBuffer(),
                    state.editorCursor(),
                    state.selector(),
                    state.footer(),
                    state.columns(),
                    state.rows(),
                    state.session(),
                    state.currentRunId(),
                    state.appliedCursor(),
                    state.seenEventIds(),
                    state.recoverableError(),
                    true);
        }
        if (action instanceof TerminalUiAction.SidebarToggled) {
            return new TerminalUiState(
                    state.header(),
                    state.loadedResources(),
                    state.transcript(),
                    state.pending(),
                    state.status(),
                    state.editorBuffer(),
                    state.editorCursor(),
                    state.selector(),
                    state.footer(),
                    state.columns(),
                    state.rows(),
                    state.session(),
                    state.currentRunId(),
                    state.appliedCursor(),
                    state.seenEventIds(),
                    state.recoverableError(),
                    state.activity(),
                    state.exitRequested(),
                    !state.sidebarVisible(),
                    true);
        }
        if (action instanceof TerminalUiAction.ThemeChanged theme) {
            return state.withThemeName(theme.themeName());
        }
        if (action instanceof TerminalUiAction.CancelArmedChanged armed) {
            String newStatus = armed.armed()
                    ? "Press esc again to cancel"
                    : ("Press esc again to cancel".equals(state.status())
                            ? (state.currentRunId().isPresent() ? "Thinking" : "Idle")
                            : state.status());
            return new TerminalUiState(
                    state.header(),
                    state.loadedResources(),
                    state.transcript(),
                    state.pending(),
                    newStatus,
                    state.editorBuffer(),
                    state.editorCursor(),
                    state.selector(),
                    state.footer(),
                    state.columns(),
                    state.rows(),
                    state.session(),
                    state.currentRunId(),
                    state.appliedCursor(),
                    state.seenEventIds(),
                    state.recoverableError(),
                    state.activity(),
                    state.exitRequested(),
                    state.sidebarVisible(),
                    state.sidebarExplicitlyToggled(),
                    state.themeName(),
                    armed.armed());
        }
        throw new IllegalArgumentException("Unsupported Terminal UI action");
    }

    private static TranscriptItem appendToolPreview(TranscriptItem current, ToolOutputPreview preview) {
        TranscriptItem updated = current.withStatus(current.status(), appendPreview(current.body(), preview));
        return current.toolDetails()
                .map(details -> updated.withToolDetails(details.withContent(appendPreview(details.content(), preview))))
                .orElse(updated);
    }

    private static String appendPreview(String body, ToolOutputPreview preview) {
        final String heading = "\n" + ToolBodyLines.OUTPUT_STREAMING + "\n";
        int headingIndex = body.indexOf(heading);
        String prefix = headingIndex >= 0 ? body.substring(0, headingIndex + heading.length()) : body + heading;
        String previous = headingIndex >= 0 ? body.substring(headingIndex + heading.length()) : "";
        String channel = previewChannelMarker(previous, preview.channel());
        String outputTruncated =
                preview.outputTruncated() ? "\n" + ToolBodyLines.EXECUTION_OUTPUT_TRUNCATED + "\n" : "";
        String previewDropped = preview.previewDropped() ? "\n" + ToolBodyLines.PREVIEW_OUTPUT_DROPPED + "\n" : "";
        String output = previous + channel + preview.text() + outputTruncated + previewDropped;
        int maximumOutput = Math.max(0, 16_384 - prefix.length());
        if (output.length() > maximumOutput) output = output.substring(output.length() - maximumOutput);
        return prefix + output;
    }

    private static String previewChannelMarker(String previous, ExecutionOutputChannel channel) {
        if (previous.isEmpty()) return channel == ExecutionOutputChannel.STDERR ? ToolBodyLines.STDERR + "\n" : "";
        int stdoutMarker = previous.lastIndexOf(ToolBodyLines.STDOUT + "\n");
        int stderrMarker = previous.lastIndexOf(ToolBodyLines.STDERR + "\n");
        ExecutionOutputChannel previousChannel =
                stderrMarker > stdoutMarker ? ExecutionOutputChannel.STDERR : ExecutionOutputChannel.STDOUT;
        if (previousChannel == channel) return "";
        String marker = channel == ExecutionOutputChannel.STDERR ? ToolBodyLines.STDERR : ToolBodyLines.STDOUT;
        return "\n" + marker + "\n";
    }

    private TerminalUiState event(TerminalUiState state, AgentRunEvent event) {
        if (state.seenEventIds().contains(event.eventId())) return state;
        if (state.currentRunId().isPresent()
                && !state.currentRunId().orElseThrow().equals(event.runId())) {
            return copyWithFailure(state, "EVENT_RUN_MISMATCH");
        }
        if (state.appliedCursor()
                        .map(value -> value.exclusiveSequence().orElse(0L))
                        .orElse(0L)
                >= event.sequence()) {
            return copyWithFailure(state, "EVENT_OUT_OF_ORDER");
        }
        List<TranscriptItem> transcript = project(state.transcript(), event);
        boolean runSettled = isTerminalRunLifecycle(event.payload());
        List<PendingMessage> pending = runSettled
                ? state.pending().stream()
                        .filter(value -> value.kind() == PendingMessage.Kind.FOLLOW_UP)
                        .toList()
                : projectPending(state.pending(), event);
        var seen = new HashSet<>(state.seenEventIds());
        seen.add(event.eventId());
        TerminalFooter footer = footer(state.footer(), event);
        Optional<String> executionFailure = event.payload() instanceof RunEventPayloads.RunLifecycle lifecycle
                        && "FAILED".equals(lifecycle.status())
                ? Optional.of(lifecycle.reasonCode())
                : Optional.empty();
        String projectedStatus = status(event, state.status());
        TerminalActivity projectedActivity = activity(state, event, projectedStatus);
        return copy(
                state,
                state.loadedResources(),
                transcript,
                pending,
                projectedStatus,
                state.editorBuffer(),
                state.editorCursor(),
                selectorAfterEvent(state.selector(), event.payload()),
                footer,
                state.columns(),
                state.rows(),
                state.session(),
                currentRunAfter(state, event, runSettled),
                Optional.of(event.cursor()),
                seen,
                executionFailure,
                projectedActivity,
                state.exitRequested(),
                state.sidebarVisible(),
                state.sidebarExplicitlyToggled(),
                runSettled ? false : state.cancelArmed());
    }

    private static Optional<TerminalSelector> selectorAfterSessionLoad(
            Optional<TerminalSelector> current, CodingSessionView view) {
        if (current.isEmpty() || !current.orElseThrow().kind().startsWith("interaction:")) {
            return current;
        }
        String expected = "interaction:"
                + view.pendingInteraction()
                        .map(interaction -> interaction.requestId().value())
                        .orElse("");
        return current.filter(selector -> selector.kind().equals(expected));
    }

    private static Optional<TerminalSelector> selectorAfterEvent(
            Optional<TerminalSelector> current, AgentRunEvent.Payload payload) {
        if (!(payload instanceof RunEventPayloads.InteractionLifecycle interaction)
                || interaction.state().equals("PENDING")
                || interaction.state().equals("REQUESTED")) {
            return current;
        }
        String completed = "interaction:" + interaction.requestId();
        return current.filter(selector -> !selector.kind().equals(completed));
    }

    private static List<TranscriptItem> project(List<TranscriptItem> current, AgentRunEvent event) {
        var items = new ArrayList<>(current);
        if (event.payload() instanceof RunEventPayloads.AssistantTextDelta payload) {
            int index = index(items, "assistant-" + payload.generationId());
            if (index < 0) {
                items.add(new TranscriptItem(
                                "assistant-" + payload.generationId(),
                                TranscriptItem.Kind.ASSISTANT,
                                "Assistant",
                                payload.textDelta(),
                                "STREAMING",
                                true)
                        .withSourceRunId(event.runId().value()));
            } else {
                items.set(index, items.get(index).append(payload.textDelta()));
            }
        } else if (event.payload() instanceof RunEventPayloads.ToolLifecycle payload) {
            String id = "tool-" + payload.toolCallId();
            String projectedStatus = displayStatus(payload);
            int existing = index(items, id);
            Optional<Long> startedAt = existing >= 0 ? items.get(existing).startedAtEpochMillis() : Optional.empty();
            if (startedAt.isEmpty()) startedAt = Optional.of(event.occurredAt().toEpochMilli());
            Optional<Long> duration = existing >= 0 ? items.get(existing).durationMillis() : Optional.empty();
            if (terminalToolStatus(projectedStatus)) {
                duration = Optional.of(Math.max(0, event.occurredAt().toEpochMilli() - startedAt.orElseThrow()));
            }
            boolean expanded = existing >= 0 && items.get(existing).expanded();
            String displayName = ToolPresentation.displayName(payload);
            upsert(
                    items,
                    new TranscriptItem(
                                    id,
                                    TranscriptItem.Kind.TOOL,
                                    toolTitle(payload, displayName),
                                    toolBody(payload, displayName),
                                    projectedStatus,
                                    expanded,
                                    Optional.empty(),
                                    startedAt,
                                    duration,
                                    payload.observation().flatMap(RunEventPayloads.ToolObservation::exitCode),
                                    Optional.of(ToolPresentation.machineName(payload)),
                                    Optional.of(new ToolDetails(
                                            toolBody(payload, displayName, false),
                                            toolTechnicalDetails(payload),
                                            existing >= 0
                                                    && items.get(existing)
                                                            .toolDetails()
                                                            .map(ToolDetails::expanded)
                                                            .orElse(false))))
                            .withSourceRunId(event.runId().value()));
        } else if (event.payload() instanceof RunEventPayloads.ResourceAvailable payload
                && !isInternalCheckpoint(payload)) {
            upsert(
                    items,
                    new TranscriptItem(
                            "resource-" + payload.reference(),
                            TranscriptItem.Kind.RESOURCE,
                            payload.title(),
                            payload.kind() + " · " + payload.reference(),
                            payload.status(),
                            false));
        } else if (event.payload() instanceof RunEventPayloads.InteractionLifecycle payload) {
            String id = "interaction-" + payload.requestId();
            int existing = index(items, id);
            Optional<ApprovalDetails> details =
                    existing < 0 ? Optional.empty() : items.get(existing).approvalDetails();
            boolean expanded = existing >= 0 && items.get(existing).expanded();
            String title = details.map(value -> "Approval · " + value.title()).orElse("Approval · " + payload.kind());
            String body = details.map(ApprovalDetails::content).orElse("Structured approval details are loading.");
            upsert(
                    items,
                    new TranscriptItem(
                            id, TranscriptItem.Kind.APPROVAL, title, body, payload.state(), expanded, details));
        } else if (event.payload() instanceof RunEventPayloads.RunLifecycle payload
                && "FAILED".equals(payload.status())) {
            String message = payload.errorMessage().orElse("Agent execution failed");
            String body = payload.diagnosticId()
                    .map(diagnosticId -> "Diagnostic ID: " + diagnosticId)
                    .orElse("No diagnostic ID was provided.");
            upsert(
                    items,
                    new TranscriptItem(
                            "run-error-" + event.runId().value(),
                            TranscriptItem.Kind.ERROR,
                            "[" + payload.reasonCode() + "] " + message,
                            body,
                            payload.status(),
                            false));
        } else if (event.payload() instanceof RunEventPayloads.DeliveryLifecycle payload) {
            upsert(
                    items,
                    new TranscriptItem(
                            "delivery-" + payload.status(),
                            TranscriptItem.Kind.RESOURCE,
                            deliveryTitle(payload),
                            deliveryBody(payload),
                            payload.status(),
                            false));
        }
        if (event.payload() instanceof RunEventPayloads.RunLifecycle lifecycle
                && TERMINAL_RUN_STATUSES.contains(lifecycle.status())) {
            if ("COMPLETED".equals(lifecycle.status()))
                markFinalResponse(items, event.runId().value());
            items.add(runSummaryItem(lifecycle, event)
                    .withSourceRunId(event.runId().value()));
        }
        return List.copyOf(items);
    }

    private static boolean isInternalCheckpoint(RunEventPayloads.ResourceAvailable payload) {
        return "checkpoint".equalsIgnoreCase(payload.kind());
    }

    private static TranscriptItem historyItem(
            io.haifa.agent.application.project.product.coding.CodingSessionHistoryItem item) {
        TranscriptItem.Kind kind =
                switch (item.kind()) {
                    case USER -> TranscriptItem.Kind.USER;
                    case ASSISTANT -> TranscriptItem.Kind.ASSISTANT;
                    case ERROR -> TranscriptItem.Kind.ERROR;
                };
        return new TranscriptItem(item.id(), kind, item.title(), item.body(), item.status(), false);
    }

    private static TerminalUiState output(TerminalUiState state, AgentRunOutputEvent event) {
        List<TranscriptItem> items = new ArrayList<>(state.transcript());
        String itemId = "assistant-" + event.generationId();
        int index = index(items, itemId);
        if (event.type() == AgentRunOutputEventType.ASSISTANT_TEXT_DELTA) {
            if (index < 0) {
                items.add(new TranscriptItem(
                                itemId,
                                TranscriptItem.Kind.ASSISTANT,
                                "Assistant",
                                event.textDelta(),
                                "STREAMING",
                                true)
                        .withSourceRunId(event.runId().value()));
            } else {
                items.set(index, items.get(index).append(event.textDelta()));
            }
        } else if (event.type() == AgentRunOutputEventType.ASSISTANT_TEXT_COMMITTED && index >= 0) {
            TranscriptItem current = items.get(index);
            items.set(index, current.withStatus("COMMITTED", current.body()));
            if (items.stream()
                    .anyMatch(item -> item.kind() == TranscriptItem.Kind.SUMMARY
                            && "COMPLETED".equals(item.status())
                            && item.sourceRunId()
                                    .filter(event.runId().value()::equals)
                                    .isPresent())) {
                markFinalResponse(items, event.runId().value());
            }
        } else if ((event.type() == AgentRunOutputEventType.RUN_OUTPUT_FAILED
                        || event.type() == AgentRunOutputEventType.RUN_OUTPUT_SUPERSEDED)
                && index >= 0) {
            items.remove(index);
        }
        return copyWithTranscript(state, List.copyOf(items));
    }

    private static void markFinalResponse(List<TranscriptItem> items, String runId) {
        for (int index = items.size() - 1; index >= 0; index--) {
            TranscriptItem item = items.get(index);
            if (item.kind() == TranscriptItem.Kind.ASSISTANT
                    && item.sourceRunId().filter(runId::equals).isPresent()) {
                items.set(index, item.withStatus("FINAL", item.body()));
                return;
            }
        }
    }

    private static String status(AgentRunEvent event, String fallback) {
        if (event.payload() instanceof RunEventPayloads.RunLifecycle lifecycle) {
            return switch (lifecycle.status()) {
                case "RUNNING" -> "THINKING";
                case "WAITING_APPROVAL", "WAITING_INTERACTION" -> "WAITING FOR APPROVAL";
                default -> lifecycle.status();
            };
        }
        if (event.payload() instanceof RunEventPayloads.ToolLifecycle lifecycle) {
            return toolActivityStatus(lifecycle.status(), fallback);
        }
        if (event.payload() instanceof RunEventPayloads.InteractionLifecycle lifecycle) {
            return switch (lifecycle.state()) {
                case "PENDING", "REQUESTED" -> "WAITING FOR APPROVAL";
                case "RESPONDED", "APPROVED" -> "WORKING";
                case "REJECTED", "EXPIRED", "CANCELLED" -> "THINKING";
                default -> fallback;
            };
        }
        if (event.payload() instanceof RunEventPayloads.RunInputLifecycle lifecycle) {
            return switch (lifecycle.state()) {
                case "ACCEPTED" -> "Applying steer";
                case "APPLIED" -> "THINKING";
                default -> fallback;
            };
        }
        if (event.payload() instanceof RunEventPayloads.DeliveryLifecycle lifecycle) {
            return switch (lifecycle.phase()) {
                case "BUDGET" -> "Budget threshold";
                default -> "Completion deferred";
            };
        }
        return fallback;
    }

    private static String toolActivityStatus(String status, String fallback) {
        return switch (status) {
            case "STARTED", "RUNNING", "WAITING", "APPROVED" -> "WORKING";
            case "SUCCEEDED", "COMPLETED", "FAILED", "DENIED", "CANCELLED", "TIMEOUT", "OUTCOME_UNKNOWN" -> "THINKING";
            default -> fallback;
        };
    }

    private static TerminalActivity activity(TerminalUiState state, AgentRunEvent event, String projectedStatus) {
        boolean statusChanged = !state.status().equalsIgnoreCase(projectedStatus);
        boolean toolBoundary = event.payload() instanceof RunEventPayloads.ToolLifecycle lifecycle
                && ("STARTED".equals(lifecycle.status()) || terminalToolStatus(lifecycle.status()));
        if (!statusChanged && !toolBoundary) return state.activity();

        String label = "";
        if (event.payload() instanceof RunEventPayloads.ToolLifecycle lifecycle
                && ("STARTED".equals(lifecycle.status()) || "RUNNING".equals(lifecycle.status()))) {
            label = boundedActivityLabel(ToolPresentation.displayName(lifecycle));
        } else if (projectedStatus.equalsIgnoreCase("WORKING")) {
            label = state.activity().label();
        }
        return state.activity().advance(label);
    }

    private static boolean terminalToolStatus(String status) {
        return Set.of("SUCCEEDED", "COMPLETED", "FAILED", "DENIED", "CANCELLED", "TIMEOUT", "OUTCOME_UNKNOWN")
                .contains(status);
    }

    private static String boundedActivityLabel(String value) {
        String label = value == null ? "" : value.strip();
        return label.length() <= 128 ? label : label.substring(0, 127) + "…";
    }

    private static Optional<AgentRunId> currentRunAfter(
            TerminalUiState state, AgentRunEvent event, boolean runSettled) {
        if (runSettled) return Optional.empty();
        if (state.currentRunId().isPresent()) return state.currentRunId();
        boolean followsSettledRun = state.appliedCursor()
                        .filter(cursor -> cursor.runId().equals(event.runId()))
                        .isPresent()
                && TERMINAL_RUN_STATUSES.contains(state.footer().runStatus());
        return followsSettledRun ? Optional.empty() : Optional.of(event.runId());
    }

    private static boolean isTerminalRunLifecycle(AgentRunEvent.Payload payload) {
        return payload instanceof RunEventPayloads.RunLifecycle lifecycle
                && TERMINAL_RUN_STATUSES.contains(lifecycle.status());
    }

    private static List<PendingMessage> projectPending(List<PendingMessage> current, AgentRunEvent event) {
        if (!(event.payload() instanceof RunEventPayloads.RunInputLifecycle lifecycle)) return current;
        var pending = new ArrayList<>(current);
        int existing = pendingIndex(pending, lifecycle.inputId());
        if ("APPLIED".equals(lifecycle.state())) {
            if (existing >= 0) pending.remove(existing);
            return List.copyOf(pending);
        }
        if (!"ACCEPTED".equals(lifecycle.state())) return current;
        String coordinate = lifecycle.applicationCoordinate().isBlank()
                ? "Accepted; waiting for a safe application point"
                : "Accepted; waiting for " + lifecycle.applicationCoordinate();
        PendingMessage item =
                new PendingMessage(lifecycle.inputId(), PendingMessage.Kind.STEER, coordinate, event.sequence());
        if (existing < 0) pending.add(item);
        else pending.set(existing, item);
        return List.copyOf(pending);
    }

    private static int pendingIndex(List<PendingMessage> values, String id) {
        for (int index = 0; index < values.size(); index++) {
            if (values.get(index).id().equals(id)) return index;
        }
        return -1;
    }

    private static String modelFooterSummary(CodingSessionView view) {
        var model = view.model().model();
        String context = model.contextWindow() >= 1_000_000
                ? (model.contextWindow() / 1_000_000) + "M"
                : model.contextWindow() >= 1_000
                        ? (model.contextWindow() / 1_000) + "K"
                        : String.valueOf(model.contextWindow());
        String summary = model.displayName() + " · "
                + model.recommendedPreferences().responseMode().name() + " · " + context;
        if (model.state().connection() == CodingModelState.Connection.LOGIN_REQUIRED) {
            return summary + " · [需要登录]";
        } else if (model.state().connection() == CodingModelState.Connection.REAUTH_REQUIRED) {
            return summary + " · [登录已失效，请重新认证]";
        }
        return summary;
    }

    private static TerminalFooter footer(TerminalFooter current, AgentRunEvent event) {
        if (!(event.payload() instanceof RunEventPayloads.RunLifecycle lifecycle)) return current;
        return new TerminalFooter(
                current.project(),
                current.gitBranch(),
                current.worktree(),
                current.worktreeBranch(),
                current.session(),
                current.metrics(),
                current.provider(),
                current.model(),
                lifecycle.status(),
                current.sandbox());
    }

    private static void upsert(List<TranscriptItem> values, TranscriptItem item) {
        int index = index(values, item.id());
        if (index < 0) values.add(item);
        else values.set(index, item);
    }

    private static int index(List<TranscriptItem> values, String id) {
        for (int index = 0; index < values.size(); index++) {
            if (values.get(index).id().equals(id)) return index;
        }
        return -1;
    }

    private static String toolBody(RunEventPayloads.ToolLifecycle lifecycle, String displayName) {
        return toolBody(lifecycle, displayName, true);
    }

    private static String toolBody(RunEventPayloads.ToolLifecycle lifecycle, String displayName, boolean technical) {
        List<String> lines = new ArrayList<>();
        String target = boundedTarget(lifecycle.targetSummary());
        if (!target.isBlank()
                && !target.equalsIgnoreCase(displayName)
                && !target.equalsIgnoreCase(ToolPresentation.machineName(lifecycle))) {
            lines.add(ToolBodyLines.TARGET_PREFIX + " " + target);
        }
        Optional<String> reasonCode = normalizedReasonCode(lifecycle.reasonCode());
        if (isOutcomeUnknown(lifecycle.status(), lifecycle.reasonCode())) {
            lines.add(ToolBodyLines.OUTCOME_PREFIX + " UNKNOWN");
            if (!OUTCOME_UNKNOWN_STATUS.equalsIgnoreCase(lifecycle.status())) {
                lines.add(ToolBodyLines.STATUS_PREFIX + " " + lifecycle.status());
            }
            if (technical) reasonCode.ifPresent(reason -> lines.add(ToolBodyLines.REASON_PREFIX + " " + reason));
            lines.add(ToolBodyLines.NEXT_PREFIX + " " + OUTCOME_UNKNOWN_NEXT_ACTION);
        } else {
            reasonCode.ifPresent(reason -> {
                if (technical) lines.add(ToolBodyLines.REASON_PREFIX + " " + reason);
                String nextAction = nextAction(reason);
                if (!nextAction.isBlank()) lines.add(ToolBodyLines.NEXT_PREFIX + " " + nextAction);
            });
        }
        lifecycle
                .observation()
                .flatMap(RunEventPayloads.ToolObservation::outputPreview)
                .ifPresent(preview -> {
                    lines.add(preview.truncated() ? ToolBodyLines.OUTPUT_TRUNCATED : ToolBodyLines.OUTPUT);
                    lines.add(preview.text());
                    if (preview.truncated()) {
                        lines.add(ToolBodyLines.OUTPUT_TRUNCATED_SUMMARY_PREFIX + preview.byteCount() + " bytes · "
                                + preview.lineCount() + " lines");
                    }
                });
        String resultRef =
                lifecycle.resultRef() == null ? "" : lifecycle.resultRef().strip();
        if (technical && !resultRef.isBlank()) lines.add(ToolBodyLines.RESULT_PREFIX + " " + resultRef);
        return String.join("\n", lines);
    }

    private static String toolTechnicalDetails(RunEventPayloads.ToolLifecycle lifecycle) {
        List<String> lines = new ArrayList<>();
        lines.add("Tool: " + ToolPresentation.machineName(lifecycle));
        lines.add("Call ID: " + lifecycle.toolCallId());
        normalizedReasonCode(lifecycle.reasonCode()).ifPresent(reason -> lines.add("Reason: " + reason));
        if (lifecycle.resultRef() != null && !lifecycle.resultRef().isBlank())
            lines.add("Result: " + lifecycle.resultRef());
        lifecycle
                .observation()
                .flatMap(RunEventPayloads.ToolObservation::processState)
                .ifPresent(process -> lines.add("Process: " + process));
        return String.join("\n", lines);
    }

    private static boolean isOutcomeUnknown(String status, String reasonCode) {
        return OUTCOME_UNKNOWN_STATUS.equalsIgnoreCase(status)
                || TOOL_OUTCOME_UNKNOWN_CODE.equalsIgnoreCase(reasonCode);
    }

    private static String displayStatus(RunEventPayloads.ToolLifecycle lifecycle) {
        return isOutcomeUnknown(lifecycle.status(), lifecycle.reasonCode())
                ? OUTCOME_UNKNOWN_STATUS
                : lifecycle.status();
    }

    private static Optional<String> normalizedReasonCode(String reasonCode) {
        if (reasonCode == null) return Optional.empty();
        String normalized = reasonCode.strip();
        if (normalized.isEmpty() || "NONE".equalsIgnoreCase(normalized)) return Optional.empty();
        return Optional.of(normalized);
    }

    private static String boundedTarget(String value) {
        String target = value == null ? "" : value.strip();
        if (target.length() <= MAX_TOOL_TARGET_LENGTH) return target;
        int end = MAX_TOOL_TARGET_LENGTH - 1;
        if (end > 0
                && end < target.length()
                && Character.isLowSurrogate(target.charAt(end))
                && Character.isHighSurrogate(target.charAt(end - 1))) {
            end--;
        }
        return target.substring(0, end) + "…";
    }

    private static String nextAction(String reasonCode) {
        return switch (reasonCode) {
            case "ABSOLUTE_WORKDIR_FORBIDDEN", "WORKDIR_INVALID", "CWD_DENIED" ->
                "Use workspaceRef with relativeWorkdir; remove absolute cd directory changes.";
            case "NETWORK_UNAVAILABLE" ->
                "If the frozen profile denied network access, request permission for this exact failed command.";
            case "HOST_AUTHENTICATION_UNAVAILABLE" ->
                "If system git or gh is already signed in, request permission to retry this exact command on the trusted host.";
            case "PERMISSION_REQUEST_NOT_ELIGIBLE" ->
                "Rewrite the command or stay within the configured workspace and sandbox boundary.";
            case "PERMISSION_REQUEST_ALREADY_USED" ->
                "Inspect the prior attempt; do not request the same permission again.";
            case "OUTCOME_UNKNOWN", "TOOL_OUTCOME_UNKNOWN" ->
                "Inspect authoritative local or remote state before deciding whether another command is safe.";
            default -> "";
        };
    }

    private static String authenticationProgressBody(CodingAuthenticationProgressView.Phase phase) {
        return switch (phase) {
            case STARTING -> "Starting the local callback and browser sign-in.";
            case WAITING_USER -> "Waiting for authorization in the browser.";
            case EXCHANGING -> "Authorization received. Exchanging it for credentials.";
            case STORING -> "Credentials received. Saving them to the system credential store.";
        };
    }

    private static String authenticationProgressStatus(CodingAuthenticationProgressView.Phase phase) {
        return switch (phase) {
            case STARTING -> "Starting sign-in";
            case WAITING_USER -> "Waiting for browser authorization";
            case EXCHANGING -> "Exchanging authorization";
            case STORING -> "Saving credentials";
        };
    }

    private static String authenticationFailureBody(String code, String lastStage) {
        String next =
                switch (code) {
                    case "AUTH_REAUTH_REQUIRED" -> "Sign-in has expired. Run /connect to sign in again.";
                    case "AUTH_LOGIN_SERVICE_UNAVAILABLE" ->
                        "The sign-in service is unavailable. Check your network and retry.";
                    case "AUTH_TOKEN_RESPONSE_INVALID", "AUTH_TOKEN_ACCOUNT_INVALID" ->
                        "The sign-in response was not accepted. Retry; if it keeps failing, check the sign-in configuration.";
                    case "AUTH_STORE_FAILED" ->
                        "Credentials were received but could not be written to the system credential store; check the credential manager permissions.";
                    case "AUTH_CALLBACK_TIMEOUT" ->
                        "Browser authorization timed out. Retry or use device code sign-in.";
                    case "AUTH_CANCELLED" -> "Sign-in was cancelled.";
                    default ->
                        "Sign-in did not complete. Retry; if it keeps failing, check the safe application log entry.";
                };
        String stage = lastStage.isBlank() ? "" : "Stage: " + lastStage + "\n";
        return stage + "Reason code: " + code + "\nNext: " + next;
    }

    private static String toolTitle(RunEventPayloads.ToolLifecycle lifecycle, String displayName) {
        String suffix = ToolPresentation.statusSuffix(lifecycle.status(), lifecycle.reasonCode());
        String machineName = ToolPresentation.machineName(lifecycle);
        String headline = toolHeadline(lifecycle);
        if (headline.isBlank() || headline.equalsIgnoreCase(displayName) || headline.equalsIgnoreCase(machineName)) {
            return displayName + suffix;
        }
        String prefix = displayName + " · ";
        int available = MAX_TRANSCRIPT_TITLE_LENGTH - prefix.length() - suffix.length();
        if (available <= 1) return displayName + suffix;
        if (headline.length() <= available) return prefix + headline + suffix;
        int end = Math.max(0, available - 1);
        if (end > 0
                && end < headline.length()
                && Character.isLowSurrogate(headline.charAt(end))
                && Character.isHighSurrogate(headline.charAt(end - 1))) {
            end--;
        }
        return prefix + headline.substring(0, end) + "…" + suffix;
    }

    /**
     * Display priority for the collapsed tool headline: model intent description, then the existing
     * safe target summary, then the tool name (handled by the caller when this returns blank).
     */
    private static String toolHeadline(RunEventPayloads.ToolLifecycle lifecycle) {
        String intent = boundedTarget(lifecycle.intentSummary());
        if (!intent.isBlank()) {
            return intent;
        }
        return boundedTarget(lifecycle.targetSummary());
    }

    private static TranscriptItem runSummaryItem(RunEventPayloads.RunLifecycle payload, AgentRunEvent event) {
        Optional<Long> duration = Optional.empty();
        String title =
                switch (payload.status()) {
                    case "COMPLETED" -> "Run completed";
                    case "FAILED" -> "Run failed · " + payload.reasonCode();
                    case "CANCELLED" -> "Run cancelled";
                    case "TIMEOUT" -> "Run timed out";
                    default -> "Run " + payload.status().toLowerCase(Locale.ROOT);
                };
        List<String> chips = new ArrayList<>();
        duration.ifPresent(value -> chips.add(TerminalDurations.human(value)));
        String chipLine = chips.isEmpty() ? "" : " · " + String.join(" · ", chips);
        List<String> body = new ArrayList<>();
        body.add("Status: " + payload.status()
                + ("NONE".equals(payload.reasonCode()) ? "" : " · " + payload.reasonCode()));
        payload.errorMessage().ifPresent(message -> body.add("Error: " + message));
        duration.ifPresent(value -> body.add("Duration: " + TerminalDurations.human(value)));
        return new TranscriptItem(
                "run-summary-" + event.runId().value(),
                TranscriptItem.Kind.SUMMARY,
                title + chipLine,
                String.join("\n", body),
                payload.status(),
                false,
                Optional.empty(),
                Optional.empty(),
                duration);
    }

    private static String deliveryTitle(RunEventPayloads.DeliveryLifecycle payload) {
        return switch (payload.phase()) {
            case "BUDGET" -> "Budget threshold";
            default -> "Completion deferred";
        };
    }

    private static String deliveryBody(RunEventPayloads.DeliveryLifecycle payload) {
        List<String> lines = new ArrayList<>();
        lines.add("Reason: " + payload.reasonCode());
        if (!payload.missingEvidence().isEmpty()) {
            lines.add("Missing: " + String.join(", ", payload.missingEvidence()));
        }
        if (payload.attempt() > 0) lines.add("Repair: " + payload.attempt());
        if (!"NONE".equals(payload.limitingResource())) {
            lines.add("Limiting resource: " + payload.limitingResource());
            lines.add("Usage: " + payload.limitingUsed() + " / " + payload.limitingLimit());
        }
        lines.add("Remaining: " + payload.remainingPercent() + "%");
        return String.join("\n", lines);
    }

    private static TerminalUiState copyWithTranscript(TerminalUiState state, List<TranscriptItem> transcript) {
        return copy(
                state,
                state.loadedResources(),
                transcript,
                state.pending(),
                state.status(),
                state.editorBuffer(),
                state.editorCursor(),
                state.selector(),
                state.footer(),
                state.columns(),
                state.rows(),
                state.session(),
                state.currentRunId(),
                state.appliedCursor(),
                state.seenEventIds(),
                state.recoverableError(),
                state.exitRequested());
    }

    private static TerminalUiState copyWithPending(TerminalUiState state, List<PendingMessage> pending) {
        return copy(
                state,
                state.loadedResources(),
                state.transcript(),
                pending,
                state.status(),
                state.editorBuffer(),
                state.editorCursor(),
                state.selector(),
                state.footer(),
                state.columns(),
                state.rows(),
                state.session(),
                state.currentRunId(),
                state.appliedCursor(),
                state.seenEventIds(),
                state.recoverableError(),
                state.exitRequested());
    }

    private static TerminalUiState copyWithStatus(TerminalUiState state, String status) {
        return copy(
                state,
                state.loadedResources(),
                state.transcript(),
                state.pending(),
                status,
                state.editorBuffer(),
                state.editorCursor(),
                state.selector(),
                state.footer(),
                state.columns(),
                state.rows(),
                state.session(),
                state.currentRunId(),
                state.appliedCursor(),
                state.seenEventIds(),
                state.recoverableError(),
                state.exitRequested());
    }

    private static TerminalUiState copyWithSelector(TerminalUiState state, Optional<TerminalSelector> selector) {
        return copy(
                state,
                state.loadedResources(),
                state.transcript(),
                state.pending(),
                state.status(),
                state.editorBuffer(),
                state.editorCursor(),
                selector,
                state.footer(),
                state.columns(),
                state.rows(),
                state.session(),
                state.currentRunId(),
                state.appliedCursor(),
                state.seenEventIds(),
                state.recoverableError(),
                state.exitRequested());
    }

    private static TerminalUiState copyWithFailure(TerminalUiState state, String code) {
        return copy(
                state,
                state.loadedResources(),
                state.transcript(),
                state.pending(),
                "Recovery required",
                state.editorBuffer(),
                state.editorCursor(),
                state.selector(),
                state.footer(),
                state.columns(),
                state.rows(),
                state.session(),
                state.currentRunId(),
                state.appliedCursor(),
                state.seenEventIds(),
                Optional.of(code),
                state.exitRequested());
    }

    private static TerminalUiState copy(
            TerminalUiState state,
            List<String> resources,
            List<TranscriptItem> transcript,
            List<PendingMessage> pending,
            String status,
            String buffer,
            int cursor,
            Optional<TerminalSelector> selector,
            TerminalFooter footer,
            int columns,
            int rows,
            Optional<io.haifa.agent.application.project.product.coding.CodingSessionView> session,
            Optional<io.haifa.agent.core.run.AgentRunId> runId,
            Optional<io.haifa.agent.runtime.api.RunEventCursor> eventCursor,
            java.util.Set<String> seen,
            Optional<String> error,
            boolean exit) {
        return copy(
                state,
                resources,
                transcript,
                pending,
                status,
                buffer,
                cursor,
                selector,
                footer,
                columns,
                rows,
                session,
                runId,
                eventCursor,
                seen,
                error,
                state.activity(),
                exit);
    }

    private static TerminalUiState copy(
            TerminalUiState state,
            List<String> resources,
            List<TranscriptItem> transcript,
            List<PendingMessage> pending,
            String status,
            String buffer,
            int cursor,
            Optional<TerminalSelector> selector,
            TerminalFooter footer,
            int columns,
            int rows,
            Optional<io.haifa.agent.application.project.product.coding.CodingSessionView> session,
            Optional<io.haifa.agent.core.run.AgentRunId> runId,
            Optional<io.haifa.agent.runtime.api.RunEventCursor> eventCursor,
            java.util.Set<String> seen,
            Optional<String> error,
            TerminalActivity activity,
            boolean exit) {
        return copy(
                state,
                resources,
                transcript,
                pending,
                status,
                buffer,
                cursor,
                selector,
                footer,
                columns,
                rows,
                session,
                runId,
                eventCursor,
                seen,
                error,
                activity,
                exit,
                state.sidebarVisible(),
                state.sidebarExplicitlyToggled());
    }

    private static TerminalUiState copy(
            TerminalUiState state,
            List<String> resources,
            List<TranscriptItem> transcript,
            List<PendingMessage> pending,
            String status,
            String buffer,
            int cursor,
            Optional<TerminalSelector> selector,
            TerminalFooter footer,
            int columns,
            int rows,
            Optional<io.haifa.agent.application.project.product.coding.CodingSessionView> session,
            Optional<io.haifa.agent.core.run.AgentRunId> runId,
            Optional<io.haifa.agent.runtime.api.RunEventCursor> eventCursor,
            java.util.Set<String> seen,
            Optional<String> error,
            TerminalActivity activity,
            boolean exit,
            boolean sidebarVisible,
            boolean sidebarExplicitlyToggled) {
        return copy(
                state,
                resources,
                transcript,
                pending,
                status,
                buffer,
                cursor,
                selector,
                footer,
                columns,
                rows,
                session,
                runId,
                eventCursor,
                seen,
                error,
                activity,
                exit,
                sidebarVisible,
                sidebarExplicitlyToggled,
                state.cancelArmed());
    }

    private static TerminalUiState copy(
            TerminalUiState state,
            List<String> resources,
            List<TranscriptItem> transcript,
            List<PendingMessage> pending,
            String status,
            String buffer,
            int cursor,
            Optional<TerminalSelector> selector,
            TerminalFooter footer,
            int columns,
            int rows,
            Optional<io.haifa.agent.application.project.product.coding.CodingSessionView> session,
            Optional<io.haifa.agent.core.run.AgentRunId> runId,
            Optional<io.haifa.agent.runtime.api.RunEventCursor> eventCursor,
            java.util.Set<String> seen,
            Optional<String> error,
            TerminalActivity activity,
            boolean exit,
            boolean sidebarVisible,
            boolean sidebarExplicitlyToggled,
            boolean cancelArmed) {
        return new TerminalUiState(
                state.header(),
                resources,
                transcript,
                pending,
                status,
                buffer,
                cursor,
                selector,
                footer,
                columns,
                rows,
                session,
                runId,
                eventCursor,
                seen,
                error,
                activity,
                exit,
                sidebarVisible,
                sidebarExplicitlyToggled,
                state.themeName(),
                cancelArmed);
    }
}
