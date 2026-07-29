package io.haifa.agent.application.coding.terminal.state;

import io.haifa.agent.application.coding.terminal.event.TerminalUiAction;
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
    private static final Set<String> TERMINAL_RUN_STATUSES = Set.of("COMPLETED", "FAILED", "CANCELLED", "TIMEOUT");

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
                    view.summary().projectId().value(),
                    "",
                    view.summary().displayName(),
                    "queue: " + view.summary().queuedCount(),
                    "",
                    "",
                    view.summary().activeRunStatus().map(Enum::name).orElse("IDLE"),
                    "");
            return copy(
                    state,
                    List.copyOf(loaded.resources()),
                    transcript,
                    sameRun ? state.pending() : List.of(),
                    active.isPresent() ? "Working" : "Idle",
                    state.editorBuffer(),
                    state.editorCursor(),
                    state.selector(),
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
                    TerminalFooter.empty(),
                    state.columns(),
                    state.rows(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    java.util.Set.of(),
                    Optional.empty(),
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
                    false));
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
        return copy(
                state,
                state.loadedResources(),
                transcript,
                pending,
                status(event, state.status()),
                state.editorBuffer(),
                state.editorCursor(),
                state.selector(),
                footer,
                state.columns(),
                state.rows(),
                state.session(),
                currentRunAfter(state, event, runSettled),
                Optional.of(event.cursor()),
                seen,
                Optional.empty(),
                state.exitRequested());
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
                            payload.displayName(),
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
        } else if (event.payload() instanceof RunEventPayloads.ResourceAvailable payload) {
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
        }
        return List.copyOf(items);
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
        if (event.payload() instanceof RunEventPayloads.RunLifecycle lifecycle) return lifecycle.status();
        if (event.payload() instanceof RunEventPayloads.ToolLifecycle lifecycle) {
            return activityStatus(lifecycle.status(), fallback);
        }
        if (event.payload() instanceof RunEventPayloads.ExecutionLifecycle lifecycle) {
            return activityStatus(lifecycle.status(), fallback);
        }
        if (event.payload() instanceof RunEventPayloads.InteractionLifecycle lifecycle) {
            return switch (lifecycle.state()) {
                case "PENDING", "REQUESTED" -> "Waiting for approval";
                case "RESPONDED", "APPROVED", "REJECTED", "EXPIRED", "CANCELLED" -> "Working";
                default -> fallback;
            };
        }
        if (event.payload() instanceof RunEventPayloads.RunInputLifecycle lifecycle) {
            return switch (lifecycle.state()) {
                case "ACCEPTED" -> "Applying steer";
                case "APPLIED" -> "Working";
                default -> fallback;
            };
        }
        return fallback;
    }

    private static String activityStatus(String status, String fallback) {
        return switch (status) {
            case "QUEUED", "REQUESTED", "STARTED", "RUNNING", "WAITING" -> "Working";
            case "FAILED", "CANCELLED" -> "Attention";
            default -> fallback;
        };
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
        }
        if (!payload.resultRef().isBlank()) lines.add("Result: " + payload.resultRef());
        return String.join("\n", lines);
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
                exit);
    }
}
