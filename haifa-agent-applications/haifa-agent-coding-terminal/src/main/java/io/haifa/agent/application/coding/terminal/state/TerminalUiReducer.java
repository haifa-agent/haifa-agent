package io.haifa.agent.application.coding.terminal.state;

import io.haifa.agent.application.coding.terminal.event.TerminalUiAction;
import io.haifa.agent.application.project.product.coding.CodingModelState;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationProgressView;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.execution.api.ExecutionOutputChannel;
import io.haifa.agent.execution.api.ToolOutputPreview;
import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.runtime.api.AgentRunOutputEvent;
import io.haifa.agent.runtime.api.AgentRunOutputEventType;
import io.haifa.agent.runtime.api.RunEventPayloads;
import io.haifa.coding.api.CodingProtocol.Entry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Deterministic, side-effect-free projection of product and committed Runtime facts. */
public final class TerminalUiReducer {
    private static final int MAX_TRANSCRIPT_TITLE_LENGTH = 256;
    private static final int MAX_USER_PREVIEW_CODE_POINTS = 8_000;
    private static final int MAX_TOOL_TARGET_LENGTH = 256;
    private static final String OUTCOME_UNKNOWN_STATUS = "OUTCOME_UNKNOWN";
    private static final String TOOL_OUTCOME_UNKNOWN_CODE = "TOOL_OUTCOME_UNKNOWN";
    private static final String OUTCOME_UNKNOWN_NEXT_ACTION =
            "Inspect authoritative local or remote state before deciding whether another command is safe.";
    private static final Set<String> TERMINAL_RUN_STATUSES = Set.of("COMPLETED", "FAILED", "CANCELLED", "TIMEOUT");
    private static final Set<String> AUTHENTICATION_PROGRESS_STATUSES =
            Set.of("STARTING", "WAITING_USER", "EXCHANGING", "STORING");

    private static String userMessagePreview(String text) {
        if (text.codePointCount(0, text.length()) <= MAX_USER_PREVIEW_CODE_POINTS) return text;
        int end = text.offsetByCodePoints(0, MAX_USER_PREVIEW_CODE_POINTS);
        return text.substring(0, end) + "\n[Preview truncated; full message submitted]";
    }

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
            Optional<io.haifa.agent.runtime.api.RunEventCursor> cursor = loaded.product()
                    ? Optional.empty()
                    : sameRun
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
            TerminalUiState loadedState = copy(
                    state,
                    List.copyOf(loaded.resources()),
                    transcript,
                    sameRun ? state.pending() : List.of(),
                    active.isPresent() ? (sameRun ? state.status() : "THINKING") : "Idle",
                    state.editorBuffer(),
                    state.editorCursor(),
                    loaded.product() && sameSession
                            ? state.selector()
                            : selectorAfterSessionLoad(state.selector(), view),
                    footer,
                    state.columns(),
                    state.rows(),
                    Optional.of(view),
                    active,
                    cursor,
                    seen,
                    Optional.empty(),
                    state.exitRequested());
            // Waiting is session-local; never carry a draft into another session.
            return sameSession ? loadedState : loadedState.withWaiting(Optional.empty());
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
                            state.exitRequested())
                    .withWaiting(Optional.empty());
        }
        if (action instanceof TerminalUiAction.ProductSessionChanged update) {
            var view = update.view();
            var displayed = state.session().orElse(null);
            if (displayed == null
                    || !view.summary().sessionId().equals(displayed.summary().sessionId())
                    || view.summary().revision() < displayed.summary().revision()
                    || view.model().revision() < displayed.model().revision()) return state;
            var prior = state.footer();
            boolean showingRun =
                    view.activeRun().map(TerminalSessionView.Run::runId).equals(state.currentRunId())
                            && state.currentRunId().isPresent();
            String task = showingRun
                    ? "task: " + view.activeRunTaskSummary().orElse("description unavailable")
                    : state.currentRunId().isPresent()
                            ? prior.session()
                            : "session: " + view.summary().displayName();
            var footer = new TerminalFooter(
                    prior.project(),
                    prior.gitBranch(),
                    prior.worktree(),
                    prior.worktreeBranch(),
                    task,
                    "queue: " + view.summary().queuedCount(),
                    view.model().model().providerDisplayName(),
                    modelFooterSummary(view),
                    prior.runStatus(),
                    prior.sandbox());
            var pending = new ArrayList<>(update.pending());
            state.pending().stream()
                    .filter(value -> value.kind() == PendingMessage.Kind.STEER)
                    .forEach(pending::add);
            return copy(
                    state,
                    state.loadedResources(),
                    state.transcript(),
                    List.copyOf(pending),
                    state.status(),
                    state.editorBuffer(),
                    state.editorCursor(),
                    state.selector(),
                    footer,
                    state.columns(),
                    state.rows(),
                    Optional.of(view),
                    state.currentRunId(),
                    state.appliedCursor(),
                    state.seenEventIds(),
                    state.recoverableError(),
                    state.exitRequested());
        }
        if (action instanceof TerminalUiAction.ProductRunSelected selection) {
            var run = selection.run();
            var runId = new AgentRunId(run.id());
            if (state.currentRunId().filter(runId::equals).isPresent()) return state;
            var prior = state.footer();
            var footer = new TerminalFooter(
                    prior.project(),
                    prior.gitBranch(),
                    prior.worktree(),
                    prior.worktreeBranch(),
                    prior.session(),
                    prior.metrics(),
                    prior.provider(),
                    prior.model(),
                    run.status(),
                    prior.sandbox());
            String status = productRunStatus(run.status(), state.status());
            return copy(
                    state,
                    state.loadedResources(),
                    state.transcript(),
                    state.pending(),
                    status,
                    state.editorBuffer(),
                    state.editorCursor(),
                    state.selector(),
                    footer,
                    state.columns(),
                    state.rows(),
                    state.session(),
                    Optional.of(runId),
                    Optional.empty(),
                    Set.of(),
                    state.recoverableError(),
                    state.activity().advance(""),
                    state.exitRequested());
        }
        if (action instanceof TerminalUiAction.ProductActivityChanged activity) {
            for (var entry : activity.entries()) state = productActivity(state, entry);
            return state;
        }
        if (action instanceof TerminalUiAction.ProductToolsChanged tools) {
            List<TranscriptItem> items = new ArrayList<>(state.transcript());
            for (Entry entry : tools.entries()) {
                var details = entry.toolDetails();
                if (details == null) continue;
                var output = Optional.ofNullable(details.output())
                        .map(value -> new ToolOutputFacts(
                                value.text(), value.truncated(), value.byteCount(), value.lineCount()));
                var tool = new ToolCardFacts(
                        entry.toolCallId(),
                        entry.title(),
                        details.name(),
                        entry.status(),
                        details.reasonCode(),
                        entry.text(),
                        details.intent(),
                        details.resultRef(),
                        Optional.ofNullable(details.processState()),
                        Optional.ofNullable(details.exitCode()).map(Math::toIntExact),
                        output);
                var started = Optional.ofNullable(details.startedAtEpochMillis());
                int priorIndex = index(items, "tool-" + entry.toolCallId());
                if (started.isEmpty()
                        && priorIndex >= 0
                        && items.get(priorIndex)
                                .sourceRunId()
                                .filter(entry.runId()::equals)
                                .isPresent()) {
                    started = items.get(priorIndex).startedAtEpochMillis();
                }
                var duration = Optional.ofNullable(details.durationMillis());
                if (duration.isEmpty() && terminalToolStatus(entry.status()) && entry.occurredAtEpochMillis() != null) {
                    duration = started.map(value -> Math.max(0L, entry.occurredAtEpochMillis() - value));
                }
                putTool(items, tool, entry.runId(), started, duration);
            }
            return productTranscript(state, items);
        }
        if (action instanceof TerminalUiAction.ProductDeliveryChanged delivery) {
            List<TranscriptItem> items = new ArrayList<>(state.transcript());
            var previous = new java.util.HashMap<String, TranscriptItem>();
            items.removeIf(item -> {
                if (!item.id().startsWith("delivery-")) return false;
                previous.put(item.id(), item);
                return true;
            });
            for (Entry entry : delivery.entries()) {
                var old = previous.get(entry.id());
                items.add(productItem(entry, old == null || old.expanded()));
            }
            if (!delivery.notices().isEmpty()) {
                items.add(new TranscriptItem(
                        "delivery-window",
                        TranscriptItem.Kind.RESOURCE,
                        "Delivery notices",
                        String.join("\n", delivery.notices()),
                        "NOTICE",
                        true));
            }
            return productTranscript(state, items);
        }
        if (action instanceof TerminalUiAction.ProductHistoryUpdated history) {
            List<TranscriptItem> items = new ArrayList<>(state.transcript());
            items.removeIf(item -> item.id().startsWith("product-live-"));
            Set<String> updatedAssistantRuns = new HashSet<>();
            for (Entry entry : history.entries()) {
                int index = index(items, entry.id());
                TranscriptItem previous = index < 0 ? null : items.get(index);
                TranscriptItem next = productItem(entry, previous != null && previous.expanded());
                if (previous != null
                        && "FINAL".equals(previous.status())
                        && previous.sourceRunId().equals(next.sourceRunId())) {
                    next = next.withStatus("FINAL", next.body());
                }
                if (index < 0) items.add(next);
                else items.set(index, next);
                if (next.kind() == TranscriptItem.Kind.ASSISTANT) {
                    next.sourceRunId().ifPresent(updatedAssistantRuns::add);
                }
            }
            items.stream()
                    .filter(item -> item.kind() == TranscriptItem.Kind.SUMMARY && "COMPLETED".equals(item.status()))
                    .flatMap(item -> item.sourceRunId().stream())
                    .filter(updatedAssistantRuns::contains)
                    .distinct()
                    .toList()
                    .forEach(runId -> markFinalResponse(items, runId));
            var live = history.live();
            if (live != null && !live.text().isEmpty()) {
                items.add(new TranscriptItem(
                                "product-live-assistant",
                                TranscriptItem.Kind.ASSISTANT,
                                "Assistant",
                                live.text() + (live.truncated() ? "\n[Live preview truncated]" : ""),
                                "STREAMING",
                                true)
                        .withSourceRunId(live.runId()));
            }
            return productTranscript(state, items);
        }
        if (action instanceof TerminalUiAction.ProductHistoryLoaded history) {
            List<TranscriptItem> items = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (Entry entry : history.entries()) {
                if (ids.add(entry.id())) items.add(productItem(entry, false));
            }
            state.transcript().stream()
                    .filter(item -> !item.id().equals("history-earlier") && ids.add(item.id()))
                    .forEach(items::add);
            if (history.earlier()) {
                items.addFirst(new TranscriptItem(
                        "history-earlier",
                        TranscriptItem.Kind.RESOURCE,
                        "Earlier history",
                        "Use /history to read the previous page.",
                        "RESTORED",
                        true));
            }
            return productTranscript(state, items);
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
            String id = shell.executionId().isEmpty() ? "shell-" + items.size() : "shell-" + shell.executionId();
            items.removeIf(item -> item.id().equals(id));
            items.add(new TranscriptItem(
                    id,
                    TranscriptItem.Kind.EXECUTION,
                    shell.command(),
                    withDestination(shell.summary(), shell.destination()),
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
                    withDestination(
                            exported.logicalPath() + " · " + exported.messageCount() + " messages",
                            exported.destination()),
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
                    .orElseGet(() -> ApprovalDetails.from(
                            interaction, presented.allowForSession(), presented.sessionApprovalUnavailableReason()));
            return reduce(
                    state,
                    new TerminalUiAction.InteractionCardChanged(
                            "interaction-" + interaction.requestId().value(),
                            interaction.state().name(),
                            clarification.isPresent(),
                            details));
        }
        if (action instanceof TerminalUiAction.InteractionCardChanged card) {
            var details = card.details();
            List<TranscriptItem> items = new ArrayList<>(state.transcript());
            int existing = index(items, card.id());
            boolean expanded = existing >= 0 && items.get(existing).expanded();
            upsert(
                    items,
                    new TranscriptItem(
                            card.id(),
                            card.clarification() ? TranscriptItem.Kind.CLARIFICATION : TranscriptItem.Kind.APPROVAL,
                            (card.clarification() ? "Clarification · " : "Approval · ") + details.title(),
                            details.content(),
                            card.status(),
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
            return event(state, received.event(), received.renderTool());
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
                    committed.id(),
                    TranscriptItem.Kind.USER,
                    "You",
                    userMessagePreview(committed.text()),
                    "SENT",
                    true));
            // Preserve the editor: callers clear it explicitly when the committed text came from
            // the buffer. Auto-submitting a separate Waiting draft must not wipe an unrelated draft.
            return copy(
                    state,
                    state.loadedResources(),
                    items,
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
        if (action instanceof TerminalUiAction.WaitingChanged waiting) {
            return state.withWaiting(waiting.waiting());
        }
        if (action instanceof TerminalUiAction.ConnectionRestored restored) {
            Optional<String> retained = state.recoverableError().filter(code -> !Set.of(
                            "CLIENT_CONTEXT_REQUIRED", "REMOTE_TRANSPORT_UNAVAILABLE", "REMOTE_STREAM_CLOSED")
                    .contains(code));
            return copyWithStatus(state, restored.status(), retained);
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
                    true,
                    state.themeName(),
                    state.cancelArmed(),
                    state.waiting(),
                    state.sessionRows(),
                    state.sessionRowSelected());
        }
        if (action instanceof TerminalUiAction.ThemeChanged theme) {
            return state.withThemeName(theme.themeName());
        }
        if (action instanceof TerminalUiAction.SessionRowsLoaded rows) {
            // A sidebar refresh never rewrites the transcript, the selection epoch or the current Run; it only
            // replaces the bounded row list, so a list poll racing a Run event cannot corrupt the main area.
            return state.withSessionRows(rows.rows());
        }
        if (action instanceof TerminalUiAction.SessionRowMoved moved) {
            return state.withSessionRowMoved(moved.delta());
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
                    armed.armed(),
                    state.waiting());
        }
        throw new IllegalArgumentException("Unsupported Terminal UI action");
    }

    private static String withDestination(String body, String destination) {
        if (destination == null || destination.isBlank()) return body;
        String prefix = "Destination: " + destination + "\n";
        if (prefix.length() + body.length() <= TranscriptItem.MAXIMUM_BODY_CHARACTERS) return prefix + body;
        String marker = "<output truncated; showing tail>\n";
        int keep = TranscriptItem.MAXIMUM_BODY_CHARACTERS - prefix.length() - marker.length();
        int start = Math.max(0, body.length() - keep);
        if (start > 0
                && Character.isLowSurrogate(body.charAt(start))
                && Character.isHighSurrogate(body.charAt(start - 1))) start++;
        return prefix + marker + body.substring(start);
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
        int maximumOutput = Math.max(0, TranscriptItem.MAXIMUM_BODY_CHARACTERS - prefix.length());
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

    private static TerminalUiState copyWithActivity(TerminalUiState state, String status, String label) {
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
                state.activity().advance(label),
                state.exitRequested());
    }

    private static String productRunStatus(String status, String previous) {
        return switch (status) {
            case "RUNNING" -> previous.equalsIgnoreCase("WORKING") ? "WORKING" : "THINKING";
            case "WAITING_APPROVAL", "WAITING_INTERACTION" -> "WAITING FOR APPROVAL";
            default -> status;
        };
    }

    private TerminalUiState productActivity(TerminalUiState state, Entry entry) {
        var run = new AgentRunId(entry.runId());
        boolean current = state.currentRunId().filter(run::equals).isPresent();
        var items = new ArrayList<>(state.transcript());
        var pending = state.pending();
        var nextRun = state.currentRunId();
        var footer = state.footer();
        var failure = state.recoverableError();
        String status = state.status();
        switch (entry.role()) {
            case "tool" -> {
                state = reduce(state, new TerminalUiAction.ProductToolsChanged(List.of(entry)));
                if (!current) return state;
                String next = toolActivityStatus(entry.status(), status);
                String label = "STARTED".equals(entry.status()) || "RUNNING".equals(entry.status())
                        ? boundedActivityLabel(entry.title())
                        : "";
                return copyWithActivity(state, next, label);
            }
            case "run-state" -> {
                boolean terminal = TERMINAL_RUN_STATUSES.contains(entry.status());
                String reason = java.util.Objects.requireNonNullElse(entry.noticeCode(), "NONE");
                if (terminal) {
                    if ("COMPLETED".equals(entry.status())) markFinalResponse(items, entry.runId());
                    upsert(
                            items,
                            runSummaryItem(
                                            entry.status(),
                                            reason,
                                            Optional.of(entry.text()).filter(value -> !value.isBlank()),
                                            entry.runId())
                                    .withSourceRunId(entry.runId()));
                    if ("FAILED".equals(entry.status())) {
                        upsert(
                                items,
                                new TranscriptItem(
                                        "run-error-" + entry.runId(),
                                        TranscriptItem.Kind.ERROR,
                                        "[" + reason + "] "
                                                + (entry.text().isBlank() ? "Agent execution failed" : entry.text()),
                                        entry.diagnosticId() == null
                                                ? "No diagnostic ID was provided."
                                                : "Diagnostic ID: " + entry.diagnosticId(),
                                        entry.status(),
                                        false));
                    }
                }
                if (current) {
                    nextRun = terminal ? Optional.empty() : Optional.of(run);
                    footer = new TerminalFooter(
                            footer.project(),
                            footer.gitBranch(),
                            footer.worktree(),
                            footer.worktreeBranch(),
                            footer.session(),
                            footer.metrics(),
                            footer.provider(),
                            footer.model(),
                            entry.status(),
                            footer.sandbox());
                    status = productRunStatus(entry.status(), status);
                    if ("FAILED".equals(entry.status())) failure = Optional.of(reason);
                    if (terminal)
                        pending = pending.stream()
                                .filter(value -> value.kind() == PendingMessage.Kind.FOLLOW_UP)
                                .toList();
                }
            }
            case "interaction-state" -> {
                int index = index(items, entry.id());
                if (index >= 0) {
                    var item = items.get(index);
                    items.set(index, item.withStatus(entry.status(), item.body()));
                }
            }
            case "input-state" -> {
                if (!current) return state;
                var changed = new ArrayList<>(pending);
                int index = pendingIndex(changed, entry.id());
                if (index >= 0) changed.remove(index);
                if ("ACCEPTED".equals(entry.status())) {
                    changed.add(new PendingMessage(
                            entry.id(),
                            PendingMessage.Kind.STEER,
                            entry.text().isBlank()
                                    ? "Accepted; waiting for a safe application point"
                                    : "Accepted; waiting for " + entry.text(),
                            entry.sequence()));
                    status = "Applying steer";
                } else if ("APPLIED".equals(entry.status())) {
                    status = "THINKING";
                }
                pending = List.copyOf(changed);
            }
            case "resource" ->
                upsert(
                        items,
                        new TranscriptItem(
                                entry.id(),
                                TranscriptItem.Kind.RESOURCE,
                                entry.title(),
                                entry.text(),
                                entry.status(),
                                false));
            default -> {
                return state;
            }
        }
        var result = copy(
                state,
                state.loadedResources(),
                List.copyOf(items),
                pending,
                status,
                state.editorBuffer(),
                state.editorCursor(),
                state.selector(),
                footer,
                state.columns(),
                state.rows(),
                state.session(),
                nextRun,
                state.appliedCursor(),
                state.seenEventIds(),
                failure,
                state.exitRequested());
        return status.equals(state.status()) ? result : copyWithActivity(result, status, "");
    }

    private TerminalUiState event(TerminalUiState state, AgentRunEvent event, boolean renderTool) {
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
        List<TranscriptItem> transcript = !renderTool && event.payload() instanceof RunEventPayloads.ToolLifecycle
                ? state.transcript()
                : project(state.transcript(), event);
        boolean runSettled = isTerminalRunLifecycle(event.payload());
        List<PendingMessage> pending = runSettled
                ? state.pending().stream()
                        .filter(value -> value.kind() == PendingMessage.Kind.FOLLOW_UP)
                        .toList()
                : projectPending(state.pending(), event);
        // SENDING is resolved only by the submission result: a pending Steer may belong to an earlier input.
        Optional<WaitingMessage> waiting = state.waiting();
        var seen = new HashSet<>(state.seenEventIds());
        seen.add(event.eventId());
        TerminalFooter footer = footer(state.footer(), event);
        Optional<String> executionFailure = event.payload() instanceof RunEventPayloads.RunLifecycle lifecycle
                        && "FAILED".equals(lifecycle.status())
                ? Optional.of(lifecycle.reasonCode())
                : Optional.empty();
        String projectedStatus = status(event, state.status());
        TerminalActivity projectedActivity = activity(state, event, projectedStatus);
        return new TerminalUiState(
                state.header(),
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
                state.themeName(),
                runSettled ? false : state.cancelArmed(),
                waiting,
                state.sessionRows(),
                state.sessionRowSelected());
    }

    private static Optional<TerminalSelector> selectorAfterSessionLoad(
            Optional<TerminalSelector> current, TerminalSessionView view) {
        if (current.isEmpty()) {
            return current;
        }
        String kind = current.orElseThrow().kind();
        String prefix;
        if (kind.startsWith("interaction:")) {
            prefix = "interaction:";
        } else if (kind.startsWith("clarification:")) {
            prefix = "clarification:";
        } else {
            return current;
        }
        String expected = prefix
                + view.pendingInteraction()
                        .map(TerminalSessionView.Pending::requestId)
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
            int existing = index(items, "tool-" + payload.toolCallId());
            Optional<Long> startedAt = existing >= 0 ? items.get(existing).startedAtEpochMillis() : Optional.empty();
            if (startedAt.isEmpty()) startedAt = Optional.of(event.occurredAt().toEpochMilli());
            Optional<Long> duration = existing >= 0 ? items.get(existing).durationMillis() : Optional.empty();
            if (terminalToolStatus(displayStatus(payload))) {
                duration = Optional.of(Math.max(0, event.occurredAt().toEpochMilli() - startedAt.orElseThrow()));
            }
            var observation = payload.observation();
            var output = observation
                    .flatMap(RunEventPayloads.ToolObservation::outputPreview)
                    .map(value ->
                            new ToolOutputFacts(value.text(), value.truncated(), value.byteCount(), value.lineCount()));
            putTool(
                    items,
                    new ToolCardFacts(
                            payload.toolCallId(),
                            payload.displayName(),
                            payload.toolName(),
                            payload.status(),
                            payload.reasonCode(),
                            payload.targetSummary(),
                            payload.intentSummary(),
                            payload.resultRef(),
                            observation.flatMap(RunEventPayloads.ToolObservation::processState),
                            observation.flatMap(RunEventPayloads.ToolObservation::exitCode),
                            output),
                    event.runId().value(),
                    startedAt,
                    duration);
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
            boolean pending = Set.of("PENDING", "REQUESTED").contains(payload.state());
            if (!pending && details.isEmpty()) {
                // Automatic approvals have no interactive card; do not retain an unhydrated placeholder.
                if (existing >= 0) items.remove(existing);
                return List.copyOf(items);
            }
            boolean expanded = existing >= 0 && items.get(existing).expanded();
            String title = details.map(value -> "Approval · " + value.title()).orElse("Approval · Approval required");
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

    private static TerminalUiState productTranscript(TerminalUiState state, List<TranscriptItem> transcript) {
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

    private static TranscriptItem productItem(Entry entry, boolean expanded) {
        var kind =
                switch (entry.kind()) {
                    case "message" ->
                        switch (entry.role()) {
                            case "user" -> TranscriptItem.Kind.USER;
                            case "assistant" -> TranscriptItem.Kind.ASSISTANT;
                            default -> TranscriptItem.Kind.ERROR;
                        };
                    case "tool" -> TranscriptItem.Kind.TOOL;
                    default -> TranscriptItem.Kind.RESOURCE;
                };
        String text = entry.text() + (entry.truncated() ? "\n[Presentation truncated]" : "");
        int maximum = TranscriptItem.MAXIMUM_BODY_CHARACTERS;
        if (text.length() > maximum) {
            int end = Character.isHighSurrogate(text.charAt(maximum - 1)) ? maximum - 1 : maximum;
            text = text.substring(0, end);
        }
        var item = new TranscriptItem(
                entry.id(),
                kind,
                boundedProductLabel(entry.title(), 256, entry.kind()),
                text,
                boundedProductLabel(entry.status(), 64, "RESTORED"),
                expanded);
        return entry.runId() == null ? item : item.withSourceRunId(entry.runId());
    }

    private static String boundedProductLabel(String label, int length, String fallback) {
        if (label.isBlank()) return fallback;
        if (label.length() <= length) return label;
        return label.substring(0, Character.isHighSurrogate(label.charAt(length - 1)) ? length - 1 : length);
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
                    && !item.id().startsWith("product-live-")
                    && item.sourceRunId().filter(runId::equals).isPresent()) {
                items.set(index, item.withStatus("FINAL", item.body()));
                return;
            }
        }
    }

    private static String status(AgentRunEvent event, String fallback) {
        if (event.payload() instanceof RunEventPayloads.RunLifecycle lifecycle) {
            return switch (lifecycle.status()) {
                case "RUNNING" -> fallback.equalsIgnoreCase("WORKING") ? "WORKING" : "THINKING";
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

    private static String modelFooterSummary(TerminalSessionView view) {
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

    private record ToolOutputFacts(String text, boolean truncated, long byteCount, long lineCount) {}

    private record ToolCardFacts(
            String toolCallId,
            String title,
            String name,
            String status,
            String reasonCode,
            String targetSummary,
            String intentSummary,
            String resultRef,
            Optional<String> processState,
            Optional<Integer> exitCode,
            Optional<ToolOutputFacts> output) {
        String machineName() {
            return ToolPresentation.machineName(title, name);
        }
    }

    private static void putTool(
            List<TranscriptItem> items,
            ToolCardFacts tool,
            String runId,
            Optional<Long> startedAt,
            Optional<Long> duration) {
        String id = "tool-" + tool.toolCallId();
        int index = index(items, id);
        var previous = index < 0 ? null : items.get(index);
        String displayName = ToolPresentation.displayName(tool.title(), tool.name());
        String status = isOutcomeUnknown(tool.status(), tool.reasonCode()) ? OUTCOME_UNKNOWN_STATUS : tool.status();
        boolean technical = previous != null
                && previous.toolDetails().map(ToolDetails::expanded).orElse(false);
        upsert(
                items,
                new TranscriptItem(
                                id,
                                TranscriptItem.Kind.TOOL,
                                toolTitle(tool, displayName),
                                toolBody(tool, displayName),
                                status,
                                previous != null && previous.expanded(),
                                Optional.empty(),
                                startedAt,
                                duration,
                                tool.exitCode(),
                                Optional.of(tool.machineName()),
                                Optional.of(new ToolDetails(
                                        toolBody(tool, displayName, false), toolTechnicalDetails(tool), technical)))
                        .withSourceRunId(runId));
    }

    private static String toolBody(ToolCardFacts tool, String displayName) {
        return toolBody(tool, displayName, true);
    }

    private static String toolBody(ToolCardFacts tool, String displayName, boolean technical) {
        List<String> lines = new ArrayList<>();
        String target = boundedTarget(tool.targetSummary());
        if (!target.isBlank()
                && !target.equalsIgnoreCase(displayName)
                && !target.equalsIgnoreCase(tool.machineName())) {
            lines.add(ToolBodyLines.TARGET_PREFIX + " " + target);
        }
        Optional<String> reasonCode = normalizedReasonCode(tool.reasonCode());
        if (isOutcomeUnknown(tool.status(), tool.reasonCode())) {
            lines.add(ToolBodyLines.OUTCOME_PREFIX + " UNKNOWN");
            if (!OUTCOME_UNKNOWN_STATUS.equalsIgnoreCase(tool.status())) {
                lines.add(ToolBodyLines.STATUS_PREFIX + " " + tool.status());
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
        tool.output().ifPresent(preview -> {
            lines.add(preview.truncated() ? ToolBodyLines.OUTPUT_TRUNCATED : ToolBodyLines.OUTPUT);
            lines.add(preview.text());
            if (preview.truncated()) {
                lines.add(ToolBodyLines.OUTPUT_TRUNCATED_SUMMARY_PREFIX + preview.byteCount() + " bytes · "
                        + preview.lineCount() + " lines");
            }
        });
        String resultRef = tool.resultRef() == null ? "" : tool.resultRef().strip();
        if (technical && !resultRef.isBlank()) lines.add(ToolBodyLines.RESULT_PREFIX + " " + resultRef);
        return String.join("\n", lines);
    }

    private static String toolTechnicalDetails(ToolCardFacts tool) {
        List<String> lines = new ArrayList<>();
        lines.add("Tool: " + tool.machineName());
        lines.add("Call ID: " + tool.toolCallId());
        normalizedReasonCode(tool.reasonCode()).ifPresent(reason -> lines.add("Reason: " + reason));
        if (tool.resultRef() != null && !tool.resultRef().isBlank()) lines.add("Result: " + tool.resultRef());
        tool.processState().ifPresent(process -> lines.add("Process: " + process));
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

    private static String toolTitle(ToolCardFacts tool, String displayName) {
        String suffix = ToolPresentation.statusSuffix(tool.status(), tool.reasonCode());
        String machineName = tool.machineName();
        String headline = toolHeadline(tool);
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
    private static String toolHeadline(ToolCardFacts tool) {
        String intent = boundedTarget(tool.intentSummary());
        if (!intent.isBlank()) {
            return intent;
        }
        return boundedTarget(tool.targetSummary());
    }

    private static TranscriptItem runSummaryItem(RunEventPayloads.RunLifecycle payload, AgentRunEvent event) {
        return runSummaryItem(
                payload.status(),
                payload.reasonCode(),
                payload.errorMessage(),
                event.runId().value());
    }

    private static TranscriptItem runSummaryItem(String status, String reason, Optional<String> error, String runId) {
        Optional<Long> duration = Optional.empty();
        String title =
                switch (status) {
                    case "COMPLETED" -> "Run completed";
                    case "FAILED" -> "Run failed · " + reason;
                    case "CANCELLED" -> "Run cancelled";
                    case "TIMEOUT" -> "Run timed out";
                    default -> "Run " + status.toLowerCase(Locale.ROOT);
                };
        List<String> chips = new ArrayList<>();
        duration.ifPresent(value -> chips.add(TerminalDurations.human(value)));
        String chipLine = chips.isEmpty() ? "" : " · " + String.join(" · ", chips);
        List<String> body = new ArrayList<>();
        body.add("Status: " + status + ("NONE".equals(reason) ? "" : " · " + reason));
        error.ifPresent(message -> body.add("Error: " + message));
        duration.ifPresent(value -> body.add("Duration: " + TerminalDurations.human(value)));
        return new TranscriptItem(
                "run-summary-" + runId,
                TranscriptItem.Kind.SUMMARY,
                title + chipLine,
                String.join("\n", body),
                status,
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
        return copyWithStatus(state, status, state.recoverableError());
    }

    private static TerminalUiState copyWithStatus(TerminalUiState state, String status, Optional<String> recovery) {
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
                recovery,
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
            Optional<TerminalSessionView> session,
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
            Optional<TerminalSessionView> session,
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
            Optional<TerminalSessionView> session,
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
            Optional<TerminalSessionView> session,
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
                cancelArmed,
                state.waiting(),
                state.sessionRows(),
                state.sessionRowSelected());
    }
}
