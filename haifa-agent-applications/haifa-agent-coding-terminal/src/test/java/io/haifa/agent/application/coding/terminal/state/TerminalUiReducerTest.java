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
        assertThat(postCompletionResource.transcript()).singleElement().satisfies(item -> {
            assertThat(item.kind()).isEqualTo(TranscriptItem.Kind.SUMMARY);
            assertThat(item.title()).isEqualTo("Run completed");
        });
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
    void rendersTypedExecutionFailureWithDiagnosticAndRecoveryAction() {
        TerminalUiState failed = reducer.reduce(
                TerminalUiState.initial(120, 40),
                new TerminalUiAction.RunEventReceived(event(
                        1,
                        "event-failed",
                        new RunEventPayloads.RunLifecycle(
                                "FAILED",
                                2,
                                "RUN_BUDGET_EXCEEDED",
                                Optional.of("Run budget exceeded"),
                                Optional.of("diag-budget")))));

        assertThat(failed.recoverableError()).contains("RUN_BUDGET_EXCEEDED");
        assertThat(failed.transcript()).hasSize(2);
        assertThat(failed.transcript().get(0)).satisfies(item -> {
            assertThat(item.kind()).isEqualTo(TranscriptItem.Kind.ERROR);
            assertThat(item.title()).isEqualTo("[RUN_BUDGET_EXCEEDED] Run budget exceeded");
            assertThat(item.body()).isEqualTo("Diagnostic ID: diag-budget");
        });
        assertThat(failed.transcript().get(1)).satisfies(item -> {
            assertThat(item.kind()).isEqualTo(TranscriptItem.Kind.SUMMARY);
            assertThat(item.title()).isEqualTo("Run failed · RUN_BUDGET_EXCEEDED");
            assertThat(item.status()).isEqualTo("FAILED");
        });
        assertThat(TerminalRecovery.fromCode("RUN_BUDGET_EXCEEDED").action())
                .contains("smaller request", "larger budget");
        assertThat(TerminalRecovery.fromCode("AGENT_LOOP_DETECTED").action())
                .contains("completed workspace changes", "new run", "more specific next step");
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
                    assertThat(item.title()).isEqualTo("workspace.write · src/App.java");
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
        assertThat(execution.status()).isEqualTo("THINKING");
    }

    @Test
    void recordsToolAndExecutionDurationsFromEventTimestamps() {
        TerminalUiState requested = reducer.reduce(
                TerminalUiState.initial(120, 40),
                new TerminalUiAction.RunEventReceived(event(
                        1,
                        "event-1",
                        new RunEventPayloads.ToolLifecycle(
                                "tool-1", "execution_run", "STARTED", "NONE", "rg search", ""),
                        Instant.parse("2026-07-27T00:00:01Z"))));
        TerminalUiState succeeded = reducer.reduce(
                requested,
                new TerminalUiAction.RunEventReceived(event(
                        2,
                        "event-2",
                        new RunEventPayloads.ToolLifecycle(
                                "tool-1", "execution_run", "SUCCEEDED", "NONE", "rg search", ""),
                        Instant.parse("2026-07-27T00:00:02.500Z"))));
        TerminalUiState executed = reducer.reduce(
                succeeded,
                new TerminalUiAction.RunEventReceived(event(
                        3,
                        "event-3",
                        new RunEventPayloads.ExecutionLifecycle(
                                "execution-1",
                                "tool-1",
                                "SUCCEEDED",
                                "rg search",
                                ".",
                                "MERGED",
                                "3 hits",
                                0,
                                false,
                                ""),
                        Instant.parse("2026-07-27T00:00:02.700Z"))));

        assertThat(executed.transcript()).hasSize(2);
        assertThat(executed.transcript().get(0).durationMillis()).contains(1500L);
        assertThat(executed.transcript().get(1).durationMillis()).contains(1700L);
        assertThat(executed.transcript().get(1).title()).isEqualTo("rg search · exit 0");
    }

    @Test
    void appendsRunSummaryWithAggregatedCountsAtTerminalRunLifecycle() {
        TerminalUiState state = TerminalUiState.initial(120, 40);
        state = reducer.reduce(
                state,
                new TerminalUiAction.RunEventReceived(event(
                        1,
                        "event-1",
                        new RunEventPayloads.ToolLifecycle("tool-1", "file_read", "STARTED", "NONE", "a.txt", ""),
                        Instant.parse("2026-07-27T00:00:01Z"))));
        state = reducer.reduce(
                state,
                new TerminalUiAction.RunEventReceived(event(
                        2,
                        "event-2",
                        new RunEventPayloads.ToolLifecycle(
                                "tool-1", "file_read", "SUCCEEDED", "NONE", "a.txt", "artifact:1"),
                        Instant.parse("2026-07-27T00:00:02Z"))));
        state = reducer.reduce(
                state,
                new TerminalUiAction.RunEventReceived(event(
                        3,
                        "event-3",
                        new RunEventPayloads.ToolLifecycle("tool-2", "file_write", "FAILED", "IO", "b.txt", ""),
                        Instant.parse("2026-07-27T00:00:03Z"))));
        state = reducer.reduce(
                state,
                new TerminalUiAction.RunEventReceived(event(
                        4,
                        "event-4",
                        new RunEventPayloads.ResourceAvailable(
                                "changes:1", "workspace-change-set", "Workspace changes", "AVAILABLE", "inspect"),
                        Instant.parse("2026-07-27T00:00:04Z"))));
        state = reducer.reduce(
                state,
                new TerminalUiAction.RunEventReceived(event(
                        5,
                        "event-5",
                        new RunEventPayloads.RunLifecycle("COMPLETED", 1, "NONE"),
                        Instant.parse("2026-07-27T00:00:05Z"))));

        TranscriptItem summary = state.transcript().getLast();
        assertThat(summary.kind()).isEqualTo(TranscriptItem.Kind.SUMMARY);
        assertThat(summary.title()).isEqualTo("Run completed · 4s · 2 tools · 1 change set");
        assertThat(summary.body())
                .contains("Tools: 1 succeeded · 1 failed", "Workspace changes: 1 change set", "Duration: 4s");
        assertThat(summary.collapsible()).isTrue();
    }

    @Test
    void advancesTheActivityClockAtToolAndModelBoundariesWithoutResettingForOutput() {
        TerminalUiState thinking = reducer.reduce(
                TerminalUiState.initial(120, 40),
                new TerminalUiAction.RunEventReceived(
                        event(1, "event-1", new RunEventPayloads.RunLifecycle("RUNNING", 1, "NONE"))));
        TerminalUiState requested = reducer.reduce(
                thinking,
                new TerminalUiAction.RunEventReceived(event(
                        2,
                        "event-2",
                        new RunEventPayloads.ToolLifecycle(
                                "tool-1", "execution.run", "REQUESTED", "NONE", "git status", ""))));
        TerminalUiState working = reducer.reduce(
                requested,
                new TerminalUiAction.RunEventReceived(event(
                        3,
                        "event-3",
                        new RunEventPayloads.ToolLifecycle(
                                "tool-1", "execution.run", "STARTED", "NONE", "git status", ""))));
        TerminalUiState executionOutput = reducer.reduce(
                working,
                new TerminalUiAction.RunEventReceived(event(
                        4,
                        "event-4",
                        new RunEventPayloads.ExecutionLifecycle(
                                "execution-1",
                                "tool-1",
                                "STREAMING",
                                "git status",
                                ".",
                                "STDOUT",
                                "clean",
                                null,
                                false,
                                ""))));
        TerminalUiState resumedThinking = reducer.reduce(
                executionOutput,
                new TerminalUiAction.RunEventReceived(event(
                        5,
                        "event-5",
                        new RunEventPayloads.ToolLifecycle(
                                "tool-1", "execution.run", "SUCCEEDED", "NONE", "git status", ""))));
        TerminalUiState modelOutput = reducer.reduce(
                resumedThinking,
                new TerminalUiAction.RunEventReceived(
                        event(6, "event-6", new RunEventPayloads.AssistantTextDelta("generation-1", "done"))));

        assertThat(thinking.status()).isEqualTo("THINKING");
        assertThat(requested.activity()).isEqualTo(thinking.activity());
        assertThat(working.status()).isEqualTo("WORKING");
        assertThat(working.activity().revision()).isEqualTo(thinking.activity().revision() + 1);
        assertThat(working.activity().label()).isEqualTo("execution.run");
        assertThat(executionOutput.activity()).isEqualTo(working.activity());
        assertThat(resumedThinking.status()).isEqualTo("THINKING");
        assertThat(resumedThinking.activity().revision())
                .isEqualTo(working.activity().revision() + 1);
        assertThat(resumedThinking.activity().label()).isEmpty();
        assertThat(modelOutput.activity()).isEqualTo(resumedThinking.activity());
    }

    @Test
    void rendersStableToolFailureCodeWithAnActionableRecoveryHint() {
        TerminalUiState failed = reducer.reduce(
                TerminalUiState.initial(120, 40),
                new TerminalUiAction.RunEventReceived(event(
                        1,
                        "event-1",
                        new RunEventPayloads.ToolLifecycle(
                                "tool-1",
                                "execution.run",
                                "FAILED",
                                "COMMAND_CLASSIFICATION_REJECTED",
                                "git status; git log -1",
                                ""))));

        assertThat(failed.transcript()).singleElement().satisfies(item -> assertThat(item.body())
                .contains(
                        "Reason: COMMAND_CLASSIFICATION_REJECTED",
                        "Next: Split compound or wrapped shell text into one simple command per tool call."));
    }

    @Test
    void doesNotRepeatTheToolNameWhenItsSafeTargetSummaryMatches() {
        TerminalUiState state = reducer.reduce(
                TerminalUiState.initial(120, 40),
                new TerminalUiAction.RunEventReceived(event(
                        1,
                        "event-1",
                        new RunEventPayloads.ToolLifecycle(
                                "tool-1", "file_stat", "SUCCEEDED", "NONE", "file_stat", "artifact:tool-1"))));

        assertThat(state.transcript()).singleElement().satisfies(item -> assertThat(item.title())
                .isEqualTo("file_stat"));
    }

    @Test
    void boundsLongSafeToolTargetsWithoutSplittingSurrogatePairs() {
        String target = "x".repeat(237) + "😀tail";

        TerminalUiState state = reducer.reduce(
                TerminalUiState.initial(120, 40),
                new TerminalUiAction.RunEventReceived(event(
                        1,
                        "event-1",
                        new RunEventPayloads.ToolLifecycle(
                                "tool-1", "workspace.read", "SUCCEEDED", "NONE", target, "artifact:tool-1"))));

        assertThat(state.transcript()).singleElement().satisfies(item -> {
            assertThat(item.title()).hasSizeLessThanOrEqualTo(256).endsWith("…");
            assertThat(item.title().chars().anyMatch(value -> Character.isSurrogate((char) value)))
                    .isFalse();
        });
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
        assertThat(updated.status()).isEqualTo("WAITING FOR APPROVAL");
    }

    @Test
    void authoritativeInteractionCompletionClosesTheMatchingSelector() {
        TerminalUiState selected = reducer.reduce(
                TerminalUiState.initial(120, 40),
                new TerminalUiAction.SelectorOpened(new TerminalSelector(
                        "interaction:interaction-1", "Approval", List.of("approve", "reject"), 0)));

        TerminalUiState completed = reducer.reduce(
                selected,
                new TerminalUiAction.RunEventReceived(event(
                        1,
                        "event-1",
                        new RunEventPayloads.InteractionLifecycle(
                                "interaction-1", "APPROVAL", "RESPONDED", "ignored"))));

        assertThat(completed.selector()).isEmpty();
        assertThat(completed.status()).isEqualTo("WORKING");
    }

    @Test
    void approvalWaitAndResponseEachStartANewTimedActivity() {
        TerminalUiState thinking = reducer.reduce(
                TerminalUiState.initial(120, 40),
                new TerminalUiAction.RunEventReceived(
                        event(1, "event-1", new RunEventPayloads.RunLifecycle("RUNNING", 1, "NONE"))));
        TerminalUiState waiting = reducer.reduce(
                thinking,
                new TerminalUiAction.RunEventReceived(event(
                        2,
                        "event-2",
                        new RunEventPayloads.InteractionLifecycle("interaction-1", "APPROVAL", "PENDING", "ignored"))));
        TerminalUiState approved = reducer.reduce(
                waiting,
                new TerminalUiAction.RunEventReceived(event(
                        3,
                        "event-3",
                        new RunEventPayloads.InteractionLifecycle(
                                "interaction-1", "APPROVAL", "APPROVED", "ignored"))));

        assertThat(waiting.status()).isEqualTo("WAITING FOR APPROVAL");
        assertThat(waiting.activity().revision()).isEqualTo(thinking.activity().revision() + 1);
        assertThat(approved.status()).isEqualTo("WORKING");
        assertThat(approved.activity().revision()).isEqualTo(waiting.activity().revision() + 1);
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
    void deliveryEventsDriveRecoveryBudgetAndCodingWorkPhaseWithoutParsingText() {
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
                                "BUDGET",
                                "BUDGET_THRESHOLD_REACHED",
                                "REMAINING_25_PERCENT",
                                List.of(),
                                25,
                                0,
                                "TOOL_CALLS",
                                24,
                                32))));
        TerminalUiState workPhase = reducer.reduce(
                budget,
                new TerminalUiAction.RunEventReceived(event(
                        4,
                        "event-4",
                        new RunEventPayloads.DeliveryLifecycle(
                                "VERIFY",
                                "ACTIVE",
                                "AUTHORITATIVE_EVIDENCE_PROJECTION",
                                List.of("VALIDATION_ATTEMPT", "DIFF_INSPECTION"),
                                42,
                                0))));

        assertThat(recovering.status()).isEqualTo("Recovering");
        assertThat(verifying.status()).isEqualTo("Verifying");
        assertThat(verifying.transcript())
                .filteredOn(item -> item.id().equals("delivery-COMPLETION_DEFERRED"))
                .singleElement()
                .satisfies(item -> assertThat(item.body())
                        .contains("DIFF_INSPECTION", "VALIDATION_ATTEMPT", "Remaining: 24%")
                        .doesNotContain("/Users/", "stderr", "fingerprint"));
        assertThat(budget.status()).isEqualTo("Budget threshold");
        assertThat(budget.transcript())
                .filteredOn(item -> item.id().equals("delivery-BUDGET_THRESHOLD_REACHED"))
                .singleElement()
                .satisfies(item -> assertThat(item.body())
                        .contains("Limiting resource: TOOL_CALLS", "Usage: 24 / 32", "Remaining: 25%"));
        assertThat(workPhase.status()).isEqualTo("Work phase: VERIFY");
        assertThat(workPhase.transcript())
                .filteredOn(item -> item.id().equals("delivery-ACTIVE"))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.title()).isEqualTo("Work phase · VERIFY");
                    assertThat(item.body()).contains("VALIDATION_ATTEMPT", "DIFF_INSPECTION", "Remaining: 42%");
                });
    }

    @Test
    void recoveryCodesHaveActionableCategoriesWithoutLeakingExceptionMessages() {
        assertThat(TerminalRecovery.fromCode("EVENT_OUT_OF_ORDER").category())
                .isEqualTo(TerminalRecovery.Category.RETRYABLE);
        assertThat(TerminalRecovery.fromCode("TOOL_RESULT_PERSISTENCE_FAILED").category())
                .isEqualTo(TerminalRecovery.Category.RETRYABLE);
        assertThat(TerminalRecovery.fromCode("WORKSPACE_CHANGE_OBSERVER_UNAVAILABLE")
                        .category())
                .isEqualTo(TerminalRecovery.Category.USER_ACTION_REQUIRED);
        assertThat(TerminalRecovery.fromCode("MODIFIED_ENTER_UNAVAILABLE").category())
                .isEqualTo(TerminalRecovery.Category.TERMINAL_CAPABILITY);
        TerminalRecovery windows = TerminalRecovery.fromCode("WINDOWS_TERMINAL_MODIFIED_ENTER_REMAP");
        assertThat(windows.displayTitle()).isEqualTo("Terminal capability");
        assertThat(windows.code()).isEqualTo("WINDOWS_TERMINAL_MODIFIED_ENTER_REMAP");
        assertThat(windows.action())
                .contains("Windows Terminal", "Ctrl+J", "custom key bindings")
                .doesNotContain("ESC[", "13;2u", "13;3u");
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
        return event(sequence, id, payload, Instant.parse("2026-07-27T00:00:00Z"));
    }

    private static AgentRunEvent event(long sequence, String id, AgentRunEvent.Payload payload, Instant occurredAt) {
        AgentRunId runId = new AgentRunId("run-1");
        return new AgentRunEvent(
                id,
                payload instanceof RunEventPayloads.AssistantTextDelta ? "assistant.text.delta" : "run.status.changed",
                "1",
                runId,
                new AgentSessionId("session-1"),
                sequence,
                new RunEventCursor(runId, "1", OptionalLong.of(sequence)),
                occurredAt,
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
