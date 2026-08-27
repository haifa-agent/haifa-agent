package io.haifa.agent.application.coding.terminal.state;

import io.haifa.agent.application.coding.terminal.event.TerminalUiAction;
import io.haifa.agent.application.project.product.coding.CodingSessionView;
import io.haifa.agent.application.project.product.coding.client.CodingAuthenticationProgressView;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.runtime.api.AgentRunOutputEvent;
import io.haifa.agent.runtime.api.AgentRunOutputEventType;
import io.haifa.agent.runtime.api.RunEventPayloads;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Deterministic, side-effect-free projection of product and committed Runtime facts. */
public final class TerminalUiReducer {
    private static final int MAX_TRANSCRIPT_TITLE_LENGTH = 256;
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
                    view.summary().displayName(),
                    "queue: " + view.summary().queuedCount(),
                    view.model().model().providerDisplayName(),
                    view.model().model().displayName(),
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
                            state.footer().project(), state.footer().gitBranch(), "", "context: —", "", "", "IDLE", ""),
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
                    shell.status(),
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
            List<TranscriptItem> items = new ArrayList<>(state.transcript());
            items.add(new TranscriptItem(
                    "auth-device-code-" + items.size(),
                    TranscriptItem.Kind.RESOURCE,
                    "ChatGPT device login",
                    "Browser URL: " + instructions.verificationUri() + "\nDevice code: " + instructions.userCode(),
                    "WAITING",
                    true));
            return copyWithStatus(copyWithTranscript(state, List.copyOf(items)), "Waiting for ChatGPT authorization");
        }
        if (action instanceof TerminalUiAction.BrowserLoginInstructionsPresented instructions) {
            List<TranscriptItem> items = new ArrayList<>(state.transcript());
            items.add(new TranscriptItem(
                    "auth-browser-instructions-" + items.size(),
                    TranscriptItem.Kind.RESOURCE,
                    instructions.connectionName() + " browser login",
                    "A browser sign-in was requested.\nIf it did not open, use this URL: "
                            + instructions.authorizationUri(),
                    "WAITING",
                    true));
            return copyWithStatus(
                    copyWithTranscript(state, List.copyOf(items)),
                    "Waiting for " + instructions.connectionName() + " authorization");
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
                            "Connected. Credentials were saved to ~/.haifa-agent/auth.json." + compatibility,
                            "CONNECTED",
                            false));
            return copyWithStatus(
                    copyWithTranscript(state, List.copyOf(items)),
                    completed.unofficialLocalCompatibility()
                            ? "Connected to " + completed.connectionName() + " (UNOFFICIAL_LOCAL_COMPAT)"
                            : "Connected to " + completed.connectionName());
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
            var details = ApprovalDetails.from(interaction);
            List<TranscriptItem> items = new ArrayList<>(state.transcript());
            upsert(
                    items,
                    new TranscriptItem(
                            "interaction-" + interaction.requestId().value(),
                            TranscriptItem.Kind.APPROVAL,
                            "Approval · " + interaction.title(),
                            details.render(),
                            interaction.state().name(),
                            true,
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
        if (action instanceof TerminalUiAction.TerminalResized resized) {
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
                    Math.max(1, resized.columns()),
                    Math.max(1, resized.rows()),
                    state.session(),
                    state.currentRunId(),
                    state.appliedCursor(),
                    state.seenEventIds(),
                    state.recoverableError(),
                    state.exitRequested());
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
        throw new IllegalArgumentException("Unsupported Terminal UI action");
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
                state.exitRequested());
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
                        true));
            } else {
                items.set(index, items.get(index).append(payload.textDelta()));
            }
        } else if (event.payload() instanceof RunEventPayloads.ToolLifecycle payload) {
            upsert(
                    items,
                    new TranscriptItem(
                            "tool-" + payload.toolCallId(),
                            TranscriptItem.Kind.TOOL,
                            toolTitle(payload),
                            toolBody(payload),
                            payload.status(),
                            false));
        } else if (event.payload() instanceof RunEventPayloads.ExecutionLifecycle payload) {
            upsert(
                    items,
                    new TranscriptItem(
                            "execution-" + payload.executionId(),
                            TranscriptItem.Kind.EXECUTION,
                            payload.commandSummary(),
                            executionBody(payload),
                            payload.status(),
                            false));
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
            String body = details.map(ApprovalDetails::render).orElse("Structured approval details are loading.");
            upsert(
                    items,
                    new TranscriptItem(
                            id,
                            TranscriptItem.Kind.APPROVAL,
                            "Approval · " + payload.kind(),
                            body,
                            payload.state(),
                            true,
                            details));
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
                        itemId, TranscriptItem.Kind.ASSISTANT, "Assistant", event.textDelta(), "STREAMING", true));
            } else {
                items.set(index, items.get(index).append(event.textDelta()));
            }
        } else if (event.type() == AgentRunOutputEventType.ASSISTANT_TEXT_COMMITTED && index >= 0) {
            TranscriptItem current = items.get(index);
            items.set(index, current.withStatus("COMMITTED", current.body()));
        } else if ((event.type() == AgentRunOutputEventType.RUN_OUTPUT_FAILED
                        || event.type() == AgentRunOutputEventType.RUN_OUTPUT_SUPERSEDED)
                && index >= 0) {
            items.remove(index);
        }
        return copyWithTranscript(state, List.copyOf(items));
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
        if (event.payload() instanceof RunEventPayloads.ExecutionLifecycle lifecycle) {
            return executionActivityStatus(lifecycle.status(), fallback);
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
                case "RECOVERING" -> "Recovering";
                case "VERIFYING" -> "Verifying";
                case "BUDGET" -> "Budget threshold";
                case "ORIENT", "PLAN", "CHANGE", "VERIFY", "REVIEW", "DELIVER", "BLOCKED" ->
                    "Work phase: " + lifecycle.phase();
                default -> "Completion deferred";
            };
        }
        return fallback;
    }

    private static String toolActivityStatus(String status, String fallback) {
        return switch (status) {
            case "STARTED", "RUNNING", "WAITING", "APPROVED" -> "WORKING";
            case "SUCCEEDED", "COMPLETED", "FAILED", "DENIED", "CANCELLED", "TIMEOUT" -> "THINKING";
            default -> fallback;
        };
    }

    private static String executionActivityStatus(String status, String fallback) {
        return switch (status) {
            case "STARTED", "RUNNING", "STREAMING", "WAITING" -> "WORKING";
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
            label = boundedActivityLabel(lifecycle.displayName());
        } else if (projectedStatus.equalsIgnoreCase("WORKING")) {
            label = state.activity().label();
        }
        return state.activity().advance(label);
    }

    private static boolean terminalToolStatus(String status) {
        return Set.of("SUCCEEDED", "COMPLETED", "FAILED", "DENIED", "CANCELLED", "TIMEOUT")
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

    private static TerminalFooter footer(TerminalFooter current, AgentRunEvent event) {
        if (!(event.payload() instanceof RunEventPayloads.RunLifecycle lifecycle)) return current;
        return new TerminalFooter(
                current.project(),
                current.gitBranch(),
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

    private static String toolBody(RunEventPayloads.ToolLifecycle payload) {
        List<String> lines = new ArrayList<>();
        lines.add("Target: " + payload.targetSummary());
        if (!payload.reasonCode().isBlank() && !"NONE".equals(payload.reasonCode())) {
            lines.add("Reason: " + payload.reasonCode());
            String nextAction = nextAction(payload.reasonCode());
            if (!nextAction.isBlank()) lines.add("Next: " + nextAction);
        }
        if (!payload.resultRef().isBlank()) lines.add("Result: " + payload.resultRef());
        return String.join("\n", lines);
    }

    private static String nextAction(String reasonCode) {
        return switch (reasonCode) {
            case "COMMAND_CLASSIFICATION_REJECTED" ->
                "Split compound or wrapped shell text into one simple command per tool call.";
            case "ABSOLUTE_WORKDIR_FORBIDDEN", "WORKDIR_INVALID", "CWD_DENIED" ->
                "Use the workspace-relative workdir field; do not prefix the command with an absolute cd.";
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
            case STARTING -> "Starting the local callback and browser sign-in flow.";
            case WAITING_USER -> "Waiting for authorization in the browser.";
            case EXCHANGING -> "Authorization received. Exchanging it for Codex credentials.";
            case STORING -> "Codex credentials received. Saving them to ~/.haifa-agent/auth.json.";
        };
    }

    private static String authenticationProgressStatus(CodingAuthenticationProgressView.Phase phase) {
        return switch (phase) {
            case STARTING -> "Starting ChatGPT sign-in";
            case WAITING_USER -> "Waiting for ChatGPT authorization";
            case EXCHANGING -> "Exchanging ChatGPT authorization";
            case STORING -> "Saving ChatGPT credentials";
        };
    }

    private static String authenticationFailureBody(String code, String lastStage) {
        String next =
                switch (code) {
                    case "AUTH_REAUTH_REQUIRED" ->
                        "The token exchange was rejected. Retry /login; if it repeats, verify the OAuth Client ID and redirect registration.";
                    case "AUTH_LOGIN_SERVICE_UNAVAILABLE" ->
                        "The token service was unavailable. Check the network and retry /login.";
                    case "AUTH_TOKEN_RESPONSE_INVALID", "AUTH_TOKEN_ACCOUNT_INVALID" ->
                        "The token response could not be accepted. Verify the client registration and retry /login.";
                    case "AUTH_STORE_FAILED" ->
                        "Token exchange completed, but ~/.haifa-agent/auth.json could not be written. Check its permissions and lock file.";
                    case "AUTH_CALLBACK_TIMEOUT" ->
                        "The local callback did not arrive before the login attempt expired. Retry /login.";
                    default ->
                        "The login did not complete. Retry /login and inspect the safe application log entry for this attempt.";
                };
        String stage = lastStage.isBlank() ? "" : "Last stage: " + lastStage + "\n";
        return stage + "Reason: " + code + "\nNext: " + next;
    }

    private static String toolTitle(RunEventPayloads.ToolLifecycle payload) {
        String target = payload.targetSummary().strip();
        if (target.isBlank() || target.equalsIgnoreCase(payload.displayName())) {
            return payload.displayName();
        }
        String prefix = payload.displayName() + " · ";
        int available = MAX_TRANSCRIPT_TITLE_LENGTH - prefix.length();
        if (target.length() <= available) return prefix + target;
        int end = Math.max(0, available - 1);
        if (end > 0
                && end < target.length()
                && Character.isLowSurrogate(target.charAt(end))
                && Character.isHighSurrogate(target.charAt(end - 1))) {
            end--;
        }
        return prefix + target.substring(0, end) + "…";
    }

    private static String executionBody(RunEventPayloads.ExecutionLifecycle payload) {
        List<String> lines = new ArrayList<>();
        if (!payload.logicalWorkdir().isBlank()) lines.add("Workdir: " + payload.logicalWorkdir());
        if (!payload.streamKind().isBlank()) lines.add("Stream: " + payload.streamKind());
        if (!payload.chunkOrRef().isBlank()) lines.add(payload.chunkOrRef());
        if (payload.exitCode() != null) lines.add("Exit: " + payload.exitCode());
        if (payload.truncated()) lines.add("Output truncated");
        if (!payload.fileChangeSetRef().isBlank()) lines.add("Changes: " + payload.fileChangeSetRef());
        return lines.isEmpty() ? "No execution details available." : String.join("\n", lines);
    }

    private static String deliveryTitle(RunEventPayloads.DeliveryLifecycle payload) {
        return switch (payload.phase()) {
            case "RECOVERING" -> "Recovering";
            case "VERIFYING" -> "Verifying";
            case "BUDGET" -> "Budget threshold";
            case "ORIENT", "PLAN", "CHANGE", "VERIFY", "REVIEW", "DELIVER", "BLOCKED" ->
                "Work phase · " + payload.phase();
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
                exit);
    }
}
