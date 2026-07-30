package io.haifa.agent.application.coding.terminal.state;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.application.coding.terminal.event.TerminalUiAction;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.runtime.api.AgentRunOutputEvent;
import io.haifa.agent.runtime.api.AgentRunOutputEventType;
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.InteractionConsequenceView;
import io.haifa.agent.runtime.api.InteractionInputContract;
import io.haifa.agent.runtime.api.InteractionKind;
import io.haifa.agent.runtime.api.InteractionRequestId;
import io.haifa.agent.runtime.api.InteractionRequesterView;
import io.haifa.agent.runtime.api.InteractionState;
import io.haifa.agent.runtime.api.InteractionTargetView;
import io.haifa.agent.runtime.api.InteractionView;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPayloads;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class TerminalUiReducerTest {
    private final TerminalUiReducer reducer = new TerminalUiReducer();

    @Test
    void projectsCommittedEventsOnceAndAdvancesCursorOnlyAfterProjection() {
        TerminalUiState initial = TerminalUiState.initial(120, 40);
        AgentRunEvent event = event(1, "event-1", new RunEventPayloads.AssistantTextDelta("g-1", "hello"));

        TerminalUiState projected = reducer.reduce(initial, new TerminalUiAction.RunEventReceived(event));
        TerminalUiState duplicate = reducer.reduce(projected, new TerminalUiAction.RunEventReceived(event));

        assertThat(projected.transcript()).hasSize(1);
        assertThat(projected.transcript().getFirst().body()).isEqualTo("hello");
        assertThat(projected.appliedCursor()).contains(event.cursor());
        assertThat(duplicate).isSameAs(projected);
    }

    @Test
    void failsClosedForOutOfOrderEvents() {
        TerminalUiState state = reducer.reduce(
                TerminalUiState.initial(120, 40),
                new TerminalUiAction.RunEventReceived(
                        event(2, "event-2", new RunEventPayloads.AssistantTextDelta("g-1", "later"))));
        TerminalUiState failed = reducer.reduce(
                state,
                new TerminalUiAction.RunEventReceived(
                        event(1, "event-1", new RunEventPayloads.AssistantTextDelta("g-1", "earlier"))));

        assertThat(failed.recoverableError()).contains("EVENT_OUT_OF_ORDER");
        assertThat(failed.appliedCursor()).isEqualTo(state.appliedCursor());
    }

    @Test
    void transientOutputDoesNotAdvanceDurableCursorAndFailedDraftIsDiscarded() {
        TerminalUiState initial = TerminalUiState.initial(120, 40);
        TerminalUiState streaming = reducer.reduce(
                initial,
                new TerminalUiAction.RunOutputReceived(
                        output(1, "generation-1", AgentRunOutputEventType.ASSISTANT_TEXT_DELTA, "draft")));
        TerminalUiState failed = reducer.reduce(
                streaming,
                new TerminalUiAction.RunOutputReceived(
                        output(2, "generation-1", AgentRunOutputEventType.RUN_OUTPUT_FAILED, "")));
        TerminalUiState replacement = reducer.reduce(
                failed,
                new TerminalUiAction.RunOutputReceived(
                        output(3, "generation-2", AgentRunOutputEventType.ASSISTANT_TEXT_DELTA, "replacement")));

        assertThat(streaming.appliedCursor()).isEmpty();
        assertThat(failed.transcript()).isEmpty();
        assertThat(replacement.transcript()).singleElement().satisfies(item -> assertThat(item.body())
                .isEqualTo("replacement"));
    }

    @Test
    void selectorDoesNotDestroyEditorBuffer() {
        TerminalUiState edited =
                reducer.reduce(TerminalUiState.initial(80, 24), new TerminalUiAction.EditorChanged("draft", 5));
        TerminalUiState selected = reducer.reduce(
                edited,
                new TerminalUiAction.SelectorOpened(new TerminalSelector("resume", "Resume", List.of("session"), 0)));
        TerminalUiState closed = reducer.reduce(selected, new TerminalUiAction.SelectorClosed());

        assertThat(closed.editorBuffer()).isEqualTo("draft");
        assertThat(closed.editorCursor()).isEqualTo(5);
        assertThat(closed.selector()).isEmpty();
    }

    @Test
    void committedRunLifecycleKeepsFooterStatusAndConsumesInternalCheckpointsWithoutRenderingThem() {
        TerminalUiState completed = reducer.reduce(
                TerminalUiState.initial(120, 40),
                new TerminalUiAction.RunEventReceived(
                        event(1, "event-1", new RunEventPayloads.RunLifecycle("COMPLETED", 1, "NONE"))));
        AgentRunEvent checkpointEvent = event(
                2,
                "event-2",
                new RunEventPayloads.ResourceAvailable(
                        "checkpoint-1", "checkpoint", "Checkpoint", "AVAILABLE", "resume"));
        TerminalUiState postCompletionResource =
                reducer.reduce(completed, new TerminalUiAction.RunEventReceived(checkpointEvent));

        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.footer().runStatus()).isEqualTo("COMPLETED");
        assertThat(completed.currentRunId()).isEmpty();
        assertThat(postCompletionResource.currentRunId()).isEmpty();
        assertThat(postCompletionResource.transcript()).isEmpty();
        assertThat(postCompletionResource.appliedCursor()).contains(checkpointEvent.cursor());
        assertThat(postCompletionResource.seenEventIds()).contains("event-2");
    }

    @Test
    void keepsUserRelevantResourcesVisible() {
        TerminalUiState resource = reducer.reduce(
                TerminalUiState.initial(120, 40),
                new TerminalUiAction.RunEventReceived(event(
                        1,
                        "event-1",
                        new RunEventPayloads.ResourceAvailable(
                                "artifact-1", "artifact", "Changed files", "AVAILABLE", "inspect"))));

        assertThat(resource.transcript()).singleElement().satisfies(item -> {
            assertThat(item.kind()).isEqualTo(TranscriptItem.Kind.RESOURCE);
            assertThat(item.title()).isEqualTo("Changed files");
            assertThat(item.body()).isEqualTo("artifact · artifact-1");
        });
    }

    @Test
    void everyTerminalRunLifecycleClearsTheCurrentRun() {
        for (String status : List.of("COMPLETED", "FAILED", "CANCELLED", "TIMEOUT")) {
            TerminalUiState settled = reducer.reduce(
                    TerminalUiState.initial(120, 40),
                    new TerminalUiAction.RunEventReceived(
                            event(1, "event-" + status, new RunEventPayloads.RunLifecycle(status, 1, "NONE"))));

            assertThat(settled.currentRunId()).as(status).isEmpty();
        }
    }

    @Test
    void upsertsToolAndExecutionLifecycleByStableIdentityWithoutDuplicateCards() {
        TerminalUiState toolRequested = reducer.reduce(
                TerminalUiState.initial(120, 40),
                new TerminalUiAction.RunEventReceived(event(
                        1,
                        "event-1",
                        new RunEventPayloads.ToolLifecycle(
                                "tool-1", "workspace.write", "REQUESTED", "NONE", "src/App.java", ""))));
        TerminalUiState toolSucceeded = reducer.reduce(
                toolRequested,
                new TerminalUiAction.RunEventReceived(event(
                        2,
                        "event-2",
                        new RunEventPayloads.ToolLifecycle(
                                "tool-1", "workspace.write", "SUCCEEDED", "NONE", "src/App.java", "artifact:tool-1"))));
        TerminalUiState execution = reducer.reduce(
                toolSucceeded,
                new TerminalUiAction.RunEventReceived(event(
                        3,
                        "event-3",
                        new RunEventPayloads.ExecutionLifecycle(
                                "execution-1",
                                "tool-1",
                                "FAILED",
                                "mvn test",
                                "workspace",
                                "STDERR",
                                "2 tests failed",
                                1,
                                false,
                                "changes:1"))));

        assertThat(execution.transcript()).hasSize(2);
        assertThat(execution.transcript())
                .filteredOn(item -> item.id().equals("tool-tool-1"))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.status()).isEqualTo("SUCCEEDED");
                    assertThat(item.body()).contains("Target: src/App.java", "Result: artifact:tool-1");
                });
        assertThat(execution.transcript())
                .filteredOn(item -> item.id().equals("execution-execution-1"))
                .singleElement()
                .satisfies(item -> assertThat(item.body())
                        .contains(
                                "Workdir: workspace",
                                "Stream: STDERR",
                                "2 tests failed",
                                "Exit: 1",
                                "Changes: changes:1"));
        assertThat(execution.status()).isEqualTo("Attention");
    }

    @Test
    void approvalUsesStructuredInteractionFieldsAndNeverProjectsLifecycleFreeText() {
        InteractionView interaction = interaction();
        TerminalUiState presented = reducer.reduce(
                TerminalUiState.initial(120, 40), new TerminalUiAction.InteractionPresented(interaction));
        TerminalUiState updated = reducer.reduce(
                presented,
                new TerminalUiAction.RunEventReceived(event(
                        1,
                        "event-1",
                        new RunEventPayloads.InteractionLifecycle(
                                "interaction-1", "APPROVAL", "PENDING", "UNSAFE_FREE_TEXT"))));

        assertThat(updated.transcript()).singleElement().satisfies(item -> {
            assertThat(item.body())
                    .contains(
                            "Action: Write workspace file",
                            "Target: src/App.java",
                            "Risk: On approval: Write file",
                            "Scope: tool · workspace.write",
                            "Network: Not declared by runtime",
                            "Reason: Allow this change?",
                            "Allowed: reject / approve")
                    .doesNotContain("UNSAFE_FREE_TEXT");
            assertThat(item.approvalDetails()).isPresent();
            assertThat(item.status()).isEqualTo("PENDING");
        });
        assertThat(updated.status()).isEqualTo("Waiting for approval");
    }

    @Test
    void steerRemainsPendingFromAcceptedUntilAppliedAndFollowUpsRemainDurable() {
        PendingMessage followUp = new PendingMessage("follow-1", PendingMessage.Kind.FOLLOW_UP, "Run tests", 1);
        TerminalUiState initial = reducer.reduce(
                TerminalUiState.initial(120, 40), new TerminalUiAction.PendingChanged(List.of(followUp)));
        TerminalUiState accepted = reducer.reduce(
                initial,
                new TerminalUiAction.RunEventReceived(event(
                        1, "event-1", new RunEventPayloads.RunInputLifecycle("input-1", "ACCEPTED", "safe-point-2"))));
        TerminalUiState duplicateRefresh =
                reducer.reduce(accepted, new TerminalUiAction.PendingChanged(List.of(followUp)));
        TerminalUiState applied = reducer.reduce(
                duplicateRefresh,
                new TerminalUiAction.RunEventReceived(event(
                        2, "event-2", new RunEventPayloads.RunInputLifecycle("input-1", "APPLIED", "safe-point-2"))));

        assertThat(accepted.pending()).hasSize(2);
        assertThat(accepted.pending()).anySatisfy(message -> {
            assertThat(message.kind()).isEqualTo(PendingMessage.Kind.STEER);
            assertThat(message.summary()).contains("safe-point-2");
        });
        assertThat(duplicateRefresh.pending()).hasSize(2);
        assertThat(applied.pending()).containsExactly(followUp);
    }

    @Test
    void sessionOrRunChangesCannotCarryAStaleSteerIntoTheNextRun() {
        TerminalUiState accepted = reducer.reduce(
                TerminalUiState.initial(120, 40),
                new TerminalUiAction.RunEventReceived(event(
                        1, "event-1", new RunEventPayloads.RunInputLifecycle("input-1", "ACCEPTED", "safe-point-2"))));

        TerminalUiState cleared = reducer.reduce(accepted, new TerminalUiAction.SessionCleared("Choose a session"));

        assertThat(cleared.pending()).isEmpty();
    }

    @Test
    void deliveryEventsDriveRecoveringVerifyingAndBudgetStateWithoutParsingText() {
        TerminalUiState recovering = reducer.reduce(
                TerminalUiState.initial(120, 40),
                new TerminalUiAction.RunEventReceived(event(
                        1,
                        "event-1",
                        new RunEventPayloads.DeliveryLifecycle(
                                "RECOVERING",
                                "RECOVERY_REQUIRED",
                                "REPEATED_ENVIRONMENT_FAILURE",
                                List.of("WORKSPACE_CHANGE"),
                                30,
                                1))));
        TerminalUiState verifying = reducer.reduce(
                recovering,
                new TerminalUiAction.RunEventReceived(event(
                        2,
                        "event-2",
                        new RunEventPayloads.DeliveryLifecycle(
                                "VERIFYING",
                                "COMPLETION_DEFERRED",
                                "DIFF_INSPECTION_MISSING",
                                List.of("DIFF_INSPECTION", "VALIDATION_ATTEMPT"),
                                24,
                                2))));
        TerminalUiState budget = reducer.reduce(
                verifying,
                new TerminalUiAction.RunEventReceived(event(
                        3,
                        "event-3",
                        new RunEventPayloads.DeliveryLifecycle(
                                "BUDGET", "BUDGET_THRESHOLD_REACHED", "REMAINING_25_PERCENT", List.of(), 25, 0))));

        assertThat(recovering.status()).isEqualTo("Recovering");
        assertThat(verifying.status()).isEqualTo("Verifying");
        assertThat(verifying.transcript())
                .filteredOn(item -> item.id().equals("delivery-COMPLETION_DEFERRED"))
                .singleElement()
                .satisfies(item -> assertThat(item.body())
                        .contains("DIFF_INSPECTION", "VALIDATION_ATTEMPT", "Remaining budget: 24%")
                        .doesNotContain("/Users/", "stderr", "fingerprint"));
        assertThat(budget.status()).isEqualTo("Budget threshold");
    }

    @Test
    void recoveryCodesHaveActionableCategoriesWithoutLeakingExceptionMessages() {
        assertThat(TerminalRecovery.fromCode("EVENT_OUT_OF_ORDER").category())
                .isEqualTo(TerminalRecovery.Category.RETRYABLE);
        assertThat(TerminalRecovery.fromCode("MODIFIED_ENTER_UNAVAILABLE").category())
                .isEqualTo(TerminalRecovery.Category.TERMINAL_CAPABILITY);
        assertThat(TerminalRecovery.fromCode("TERMINAL_FAILURE").category())
                .isEqualTo(TerminalRecovery.Category.TERMINAL_FAILURE);
        assertThat(TerminalRecovery.fromCode("UNKNOWN_SAFE_CODE").action()).contains("draft is preserved");
    }

    private static InteractionView interaction() {
        return new InteractionView(
                new InteractionRequestId("interaction-1"),
                new AgentRunId("run-1"),
                new AgentSessionId("session-1"),
                0,
                InteractionKind.APPROVAL,
                InteractionState.PENDING,
                "Write workspace file",
                "Allow this change?",
                List.of(InteractionAction.REJECT, InteractionAction.APPROVE),
                InteractionInputContract.NONE,
                new InteractionTargetView(
                        "tool", "workspace.write", Optional.of("1"), Optional.empty(), "src/App.java"),
                new InteractionRequesterView("agent", "Coding Agent"),
                Instant.parse("2026-07-27T00:00:00Z"),
                Instant.parse("2026-07-27T00:01:00Z"),
                new InteractionConsequenceView("Write file", "Skip tool", "Expire request"));
    }

    private static AgentRunEvent event(long sequence, String id, AgentRunEvent.Payload payload) {
        AgentRunId runId = new AgentRunId("run-1");
        return new AgentRunEvent(
                id,
                payload instanceof RunEventPayloads.AssistantTextDelta ? "assistant.text.delta" : "run.status.changed",
                "1",
                runId,
                new AgentSessionId("session-1"),
                sequence,
                new RunEventCursor(runId, "1", OptionalLong.of(sequence)),
                Instant.parse("2026-07-27T00:00:00Z"),
                Optional.empty(),
                Optional.empty(),
                payload);
    }

    private static AgentRunOutputEvent output(
            long sequence, String generationId, AgentRunOutputEventType type, String text) {
        return new AgentRunOutputEvent(
                new AgentRunId("run-1"),
                generationId,
                generationId,
                1,
                sequence,
                type,
                text,
                Instant.parse("2026-07-27T00:00:00Z"));
    }
}
