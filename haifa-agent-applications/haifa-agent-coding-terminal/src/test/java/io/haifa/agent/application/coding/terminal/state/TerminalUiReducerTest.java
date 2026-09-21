package io.haifa.agent.application.coding.terminal.state;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.application.coding.terminal.event.TerminalUiAction;
import io.haifa.agent.application.project.product.coding.CodingModelControls;
import io.haifa.agent.application.project.product.coding.CodingModelOption;
import io.haifa.agent.application.project.product.coding.CodingModelPreferences;
import io.haifa.agent.application.project.product.coding.CodingModelSelection;
import io.haifa.agent.application.project.product.coding.CodingModelState;
import io.haifa.agent.application.project.product.coding.CodingSessionHistoryItem;
import io.haifa.agent.application.project.product.coding.CodingSessionHistoryPage;
import io.haifa.agent.application.project.product.coding.CodingSessionSummary;
import io.haifa.agent.application.project.product.coding.CodingSessionView;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.session.AgentSessionStatus;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.execution.api.ExecutionOutputChannel;
import io.haifa.agent.execution.api.ToolOutputPreview;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.runtime.api.AgentRunOutputEvent;
import io.haifa.agent.runtime.api.AgentRunOutputEventType;
import io.haifa.agent.runtime.api.ApprovalPresentation;
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
import java.util.Set;
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
    }

    @Test
    void upsertsToolLifecycleByStableIdentityWithoutDuplicateCards() {
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
        assertThat(toolSucceeded.transcript())
                .filteredOn(item -> item.id().equals("tool-tool-1"))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.status()).isEqualTo("SUCCEEDED");
                    assertThat(item.title()).isEqualTo("workspace.write · src/App.java");
                    assertThat(item.body()).contains("Target: src/App.java", "Result: artifact:tool-1");
                });
    }

    @Test
    void ignoresLatePreviewAfterAuthoritativeToolCompletion() {
        TerminalUiState runStarted = reducer.reduce(
                TerminalUiState.initial(120, 40),
                new TerminalUiAction.RunEventReceived(
                        event(1, "run-started", new RunEventPayloads.RunLifecycle("STARTED", 1, "NONE"))));
        TerminalUiState requested = reducer.reduce(
                runStarted,
                new TerminalUiAction.RunEventReceived(event(
                        2,
                        "event-1",
                        new RunEventPayloads.ToolLifecycle(
                                "tool-1", "execution_run", "STARTED", "NONE", "echo hi", ""))));
        TerminalUiState stdoutPreviewed = reducer.reduce(
                requested,
                new TerminalUiAction.ToolOutputPreviewReceived(new ToolOutputPreview(
                        new AgentRunId("run-1"),
                        new ToolCallId("tool-1"),
                        ExecutionOutputChannel.STDOUT,
                        "live output",
                        false,
                        false)));
        TerminalUiState stderrPreviewed = reducer.reduce(
                stdoutPreviewed,
                new TerminalUiAction.ToolOutputPreviewReceived(new ToolOutputPreview(
                        new AgentRunId("run-1"),
                        new ToolCallId("tool-1"),
                        ExecutionOutputChannel.STDERR,
                        "warning",
                        true,
                        false)));
        TerminalUiState repeatedStderr = reducer.reduce(
                stderrPreviewed,
                new TerminalUiAction.ToolOutputPreviewReceived(new ToolOutputPreview(
                        new AgentRunId("run-1"),
                        new ToolCallId("tool-1"),
                        ExecutionOutputChannel.STDERR,
                        "warning again",
                        false,
                        false)));
        TerminalUiState previewed = reducer.reduce(
                repeatedStderr,
                new TerminalUiAction.ToolOutputPreviewReceived(new ToolOutputPreview(
                        new AgentRunId("run-1"),
                        new ToolCallId("tool-1"),
                        ExecutionOutputChannel.STDOUT,
                        "resumed",
                        false,
                        true)));
        TerminalUiState completed = reducer.reduce(
                previewed,
                new TerminalUiAction.RunEventReceived(event(
                        3,
                        "event-2",
                        new RunEventPayloads.ToolLifecycle(
                                "tool-1", "execution_run", "SUCCEEDED", "NONE", "echo hi", "final result"))));
        TerminalUiState latePreview = reducer.reduce(
                completed,
                new TerminalUiAction.ToolOutputPreviewReceived(new ToolOutputPreview(
                        new AgentRunId("run-1"),
                        new ToolCallId("tool-1"),
                        ExecutionOutputChannel.STDOUT,
                        "late output",
                        false,
                        false)));

        assertThat(previewed.transcript())
                .filteredOn(item -> item.id().equals("tool-tool-1"))
                .singleElement()
                .satisfies(item -> assertThat(item.body())
                        .contains(
                                "Target: echo hi\nOutput (streaming):\n",
                                "live output",
                                "[stderr]\nwarning",
                                "warning again",
                                "[stdout]\nresumed",
                                "[execution output truncated]",
                                "[preview output dropped]"))
                .satisfies(item -> assertThat(item.body()).containsOnlyOnce("[stderr]\n"));
        assertThat(latePreview.transcript())
                .filteredOn(item -> item.id().equals("tool-tool-1"))
                .singleElement()
                .satisfies(item ->
                        assertThat(item.body()).contains("Result: final result").doesNotContain("late output"));
    }

    @Test
    void recordsToolDurationFromEventTimestamps() {
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
        assertThat(succeeded.transcript()).singleElement().satisfies(item -> {
            assertThat(item.durationMillis()).contains(1500L);
            assertThat(item.title()).isEqualTo("execution_run · rg search");
        });
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
        assertThat(summary.title()).isEqualTo("Run completed");
        assertThat(summary.durationMillis()).isEmpty();
        assertThat(summary.body())
                .contains("Status: COMPLETED")
                .doesNotContain("Duration:", "Tools:", "Workspace changes:");
        assertThat(summary.collapsible()).isTrue();
    }

    @Test
    void restoredHistoryDoesNotProduceBogusDurationOnSubsequentRunCompletion() {
        AgentSessionId sessionId = new AgentSessionId("session-1");
        CodingSessionSummary summary = new CodingSessionSummary(
                sessionId,
                new ProjectId("project-1"),
                "session",
                AgentSessionStatus.ACTIVE,
                Optional.empty(),
                Optional.empty(),
                0,
                Instant.EPOCH,
                0);
        CodingSessionView sessionView = new CodingSessionView(
                summary, Optional.empty(), Optional.empty(), Optional.empty(), "sha256:test", "cli-coding@1.0.0");

        TerminalUiState state = reducer.reduce(
                TerminalUiState.initial(120, 40), new TerminalUiAction.SessionLoaded(sessionView, List.of()));

        CodingSessionHistoryPage history = new CodingSessionHistoryPage(
                sessionId,
                List.of(
                        new CodingSessionHistoryItem(
                                "history-1",
                                CodingSessionHistoryItem.Kind.USER,
                                "You",
                                "old question",
                                "COMPLETED",
                                1,
                                Instant.parse("2026-07-26T00:00:00Z")),
                        new CodingSessionHistoryItem(
                                "history-2",
                                CodingSessionHistoryItem.Kind.ASSISTANT,
                                "Assistant",
                                "old answer",
                                "COMPLETED",
                                2,
                                Instant.parse("2026-07-26T00:00:05Z"))),
                false);

        state = reducer.reduce(state, new TerminalUiAction.HistoryLoaded(history));

        state = reducer.reduce(
                state,
                new TerminalUiAction.RunEventReceived(event(
                        1,
                        "event-1",
                        new RunEventPayloads.ToolLifecycle("tool-1", "file_read", "STARTED", "NONE", "a.txt", ""),
                        Instant.parse("2026-07-27T10:00:00Z"))));
        state = reducer.reduce(
                state,
                new TerminalUiAction.RunEventReceived(event(
                        2,
                        "event-2",
                        new RunEventPayloads.RunLifecycle("COMPLETED", 1, "NONE"),
                        Instant.parse("2026-07-27T10:00:05Z"))));

        TranscriptItem summaryItem = state.transcript().getLast();
        assertThat(summaryItem.kind()).isEqualTo(TranscriptItem.Kind.SUMMARY);
        assertThat(summaryItem.title()).isEqualTo("Run completed");
        assertThat(summaryItem.durationMillis()).isEmpty();
        assertThat(summaryItem.body()).contains("Status: COMPLETED").doesNotContain("Duration:");
    }

    @Test
    void retainsLocalShellExecutionInTranscriptWithoutSummaryAggregation() {
        TerminalUiState state = reducer.reduce(
                TerminalUiState.initial(120, 40),
                new TerminalUiAction.ShellCompleted("!pwd", "D:/workspace", "EXITED"));
        state = reducer.reduce(
                state,
                new TerminalUiAction.RunEventReceived(event(
                        1,
                        "event-1",
                        new RunEventPayloads.RunLifecycle("COMPLETED", 1, "NONE"),
                        Instant.parse("2026-07-27T00:00:01Z"))));

        assertThat(state.transcript()).anySatisfy(item -> {
            assertThat(item.kind()).isEqualTo(TranscriptItem.Kind.EXECUTION);
            assertThat(item.status()).isEqualTo("EXITED");
        });
        assertThat(state.transcript().getLast().body())
                .contains("Status: COMPLETED")
                .doesNotContain("Tools:");
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
                                "tool-1", "execution_run", "REQUESTED", "NONE", "git status", ""))));
        TerminalUiState working = reducer.reduce(
                requested,
                new TerminalUiAction.RunEventReceived(event(
                        3,
                        "event-3",
                        new RunEventPayloads.ToolLifecycle(
                                "tool-1", "execution_run", "STARTED", "NONE", "git status", ""))));
        TerminalUiState resumedThinking = reducer.reduce(
                working,
                new TerminalUiAction.RunEventReceived(event(
                        4,
                        "event-4",
                        new RunEventPayloads.ToolLifecycle(
                                "tool-1", "execution_run", "SUCCEEDED", "NONE", "git status", ""))));
        TerminalUiState modelOutput = reducer.reduce(
                resumedThinking,
                new TerminalUiAction.RunEventReceived(
                        event(5, "event-5", new RunEventPayloads.AssistantTextDelta("generation-1", "done"))));

        assertThat(thinking.status()).isEqualTo("THINKING");
        assertThat(requested.activity()).isEqualTo(thinking.activity());
        assertThat(working.status()).isEqualTo("WORKING");
        assertThat(working.activity().revision()).isEqualTo(thinking.activity().revision() + 1);
        assertThat(working.activity().label()).isEqualTo("execution_run");
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
                                "execution_run",
                                "FAILED",
                                "ABSOLUTE_WORKDIR_FORBIDDEN",
                                "cd /workspace && git status",
                                ""))));

        assertThat(failed.transcript()).singleElement().satisfies(item -> assertThat(item.body())
                .contains(
                        "Reason: ABSOLUTE_WORKDIR_FORBIDDEN",
                        "Next: Use workspaceRef with relativeWorkdir; remove absolute cd directory changes."));
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
            assertThat(item.title()).isEqualTo("Approval · Write workspace file");
            assertThat(item.body()).isEqualTo("Allow this change?").doesNotContain("UNSAFE_FREE_TEXT");
            assertThat(item.approvalDetails()).hasValueSatisfying(details -> {
                assertThat(details.title()).isEqualTo("Write workspace file");
                assertThat(details.purpose()).isEqualTo("Write file");
                assertThat(details.content()).isEqualTo("Allow this change?");
                assertThat(details.technical()).isEmpty();
                assertThat(details.allowedActions()).containsExactly("reject", "approve");
            });
            assertThat(item.status()).isEqualTo("PENDING");
        });
        assertThat(updated.status()).isEqualTo("WAITING FOR APPROVAL");
    }

    @Test
    void approvalPresentationIsProjectedIntoStructuredDetails() {
        TerminalUiState presented = reducer.reduce(
                TerminalUiState.initial(120, 40),
                new TerminalUiAction.InteractionPresented(interactionWithPresentation()));

        assertThat(presented.transcript()).singleElement().satisfies(item -> {
            assertThat(item.title()).isEqualTo("Approval · 执行 PowerShell 命令");
            assertThat(item.body()).isEqualTo("Start-Sleep -Seconds 4");
            assertThat(item.approvalDetails()).hasValueSatisfying(details -> {
                assertThat(details.contentType()).isEqualTo("PowerShell");
                assertThat(details.environment()).singleElement().satisfies(fact -> assertThat(fact.value())
                        .isEqualTo("本机环境"));
                assertThat(details.technical()).singleElement().satisfies(fact -> assertThat(fact.value())
                        .isEqualTo("digest-123"));
                assertThat(details.risk()).contains("HIGH");
            });
        });
    }

    @Test
    void approvalPresentationPreservesExpandedDetailsWhenRePresented() {
        TerminalUiState presented = reducer.reduce(
                TerminalUiState.initial(120, 40),
                new TerminalUiAction.InteractionPresented(interactionWithPresentation()));
        TerminalUiState toggled =
                reducer.reduce(presented, new TerminalUiAction.ToggleExpanded("interaction-interaction-presentation"));
        assertThat(toggled.transcript()).singleElement().satisfies(item -> assertThat(item.expanded())
                .isTrue());

        TerminalUiState represented =
                reducer.reduce(toggled, new TerminalUiAction.InteractionPresented(interactionWithPresentation()));

        assertThat(represented.transcript()).singleElement().satisfies(item -> assertThat(item.expanded())
                .isTrue());
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
    void deliveryEventsDriveCompletionAndBudgetWithoutParsingText() {
        TerminalUiState firstDeferral = reducer.reduce(
                TerminalUiState.initial(120, 40),
                new TerminalUiAction.RunEventReceived(event(
                        1,
                        "event-1",
                        new RunEventPayloads.DeliveryLifecycle(
                                "COMPLETION",
                                "COMPLETION_DEFERRED",
                                "REQUIRED_ARTIFACT_MISSING",
                                List.of("REQUIRED_ARTIFACT"),
                                30,
                                1))));
        TerminalUiState secondDeferral = reducer.reduce(
                firstDeferral,
                new TerminalUiAction.RunEventReceived(event(
                        2,
                        "event-2",
                        new RunEventPayloads.DeliveryLifecycle(
                                "COMPLETION",
                                "COMPLETION_DEFERRED",
                                "PRODUCT_REQUIREMENT_MISSING",
                                List.of("PRODUCT_REQUIREMENT", "SECOND_REQUIREMENT"),
                                24,
                                2))));
        TerminalUiState budget = reducer.reduce(
                secondDeferral,
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

        assertThat(firstDeferral.status()).isEqualTo("Completion deferred");
        assertThat(secondDeferral.status()).isEqualTo("Completion deferred");
        assertThat(secondDeferral.transcript())
                .filteredOn(item -> item.id().equals("delivery-COMPLETION_DEFERRED"))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.title()).isEqualTo("Completion deferred");
                    assertThat(item.body())
                            .contains("PRODUCT_REQUIREMENT", "SECOND_REQUIREMENT", "Remaining: 24%")
                            .doesNotContain("/Users/", "stderr", "fingerprint");
                });
        assertThat(budget.status()).isEqualTo("Budget threshold");
        assertThat(budget.transcript())
                .filteredOn(item -> item.id().equals("delivery-BUDGET_THRESHOLD_REACHED"))
                .singleElement()
                .satisfies(item -> assertThat(item.body())
                        .contains("Limiting resource: TOOL_CALLS", "Usage: 24 / 32", "Remaining: 25%"));
    }

    @Test
    void recoveryCodesHaveActionableCategoriesWithoutLeakingExceptionMessages() {
        assertThat(TerminalRecovery.fromCode("EVENT_OUT_OF_ORDER").category())
                .isEqualTo(TerminalRecovery.Category.RETRYABLE);
        assertThat(TerminalRecovery.fromCode("TOOL_RESULT_PERSISTENCE_FAILED").category())
                .isEqualTo(TerminalRecovery.Category.RETRYABLE);
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

    private static InteractionView interactionWithPresentation() {
        return new InteractionView(
                new InteractionRequestId("interaction-presentation"),
                new AgentRunId("run-1"),
                new AgentSessionId("session-1"),
                0,
                InteractionKind.APPROVAL,
                InteractionState.PENDING,
                "Approval required",
                "Mode: SCRIPT\nRisks: HIGH",
                List.of(InteractionAction.REJECT, InteractionAction.APPROVE),
                InteractionInputContract.NONE,
                new InteractionTargetView(
                        "tool",
                        "execution_run",
                        Optional.empty(),
                        Optional.empty(),
                        "Approval required for execution_run"),
                new InteractionRequesterView("agent", "Personal Assistant"),
                Instant.parse("2026-07-27T00:00:00Z"),
                Optional.of(Instant.parse("2026-07-27T00:01:00Z")),
                new InteractionConsequenceView(
                        "Runtime will revalidate the target before continuing", "The action will not run", "Expire"),
                Optional.of(new ApprovalPresentation(
                        "执行 PowerShell 命令",
                        "为了观察终端工具调用的实际效果。",
                        "PowerShell",
                        "Start-Sleep -Seconds 4",
                        List.of(new ApprovalPresentation.Fact("执行位置", "本机环境")),
                        List.of(new ApprovalPresentation.Fact("调用摘要", "digest-123")),
                        Optional.of("HIGH"))));
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

    @Test
    void modelFooterSummaryIndicatesUnauthenticatedModel() {
        AgentSessionId sessionId = new AgentSessionId("session-1");
        CodingSessionSummary summary = new CodingSessionSummary(
                sessionId,
                new ProjectId("project-1"),
                "session",
                AgentSessionStatus.ACTIVE,
                Optional.empty(),
                Optional.empty(),
                0,
                Instant.EPOCH,
                0);
        CodingModelOption unreadyOption = new CodingModelOption(
                "claude-3-7-sonnet",
                "Claude 3.7 Sonnet",
                "anthropic",
                "Anthropic",
                Set.of("TEXT_CHAT", "TOOL_CALLING"),
                200_000,
                16_000,
                new CodingModelState(
                        CodingModelState.Connection.LOGIN_REQUIRED,
                        CodingModelState.BindingAvailability.AVAILABLE,
                        CodingModelState.RuntimeStatus.NORMAL,
                        CodingModelState.RunScope.IDLE),
                "",
                CodingModelControls.unavailable(),
                CodingModelPreferences.recommended(),
                Optional.empty());
        CodingSessionView sessionView = new CodingSessionView(
                summary,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                "sha256:test",
                "cli-coding@1.0.0",
                new CodingModelSelection(unreadyOption, 0, true),
                Optional.empty());

        TerminalUiState state = reducer.reduce(
                TerminalUiState.initial(120, 40), new TerminalUiAction.SessionLoaded(sessionView, List.of()));

        assertThat(state.footer().model()).contains("Claude 3.7 Sonnet");
        assertThat(state.footer().model()).endsWith(" · [需要登录]");
    }

    @Test
    void modelFooterSummaryIndicatesReauthRequiredModel() {
        TerminalUiReducer reducer = new TerminalUiReducer();
        CodingSessionSummary summary = new CodingSessionSummary(
                new AgentSessionId("session-1"),
                new ProjectId("project-1"),
                "session",
                AgentSessionStatus.ACTIVE,
                Optional.empty(),
                Optional.empty(),
                0,
                Instant.EPOCH,
                0);
        CodingModelOption reauthOption = new CodingModelOption(
                "claude-3-7-sonnet",
                "Claude 3.7 Sonnet",
                "anthropic",
                "Anthropic",
                Set.of("TEXT_CHAT", "TOOL_CALLING"),
                200_000,
                16_000,
                new CodingModelState(
                        CodingModelState.Connection.REAUTH_REQUIRED,
                        CodingModelState.BindingAvailability.AVAILABLE,
                        CodingModelState.RuntimeStatus.NORMAL,
                        CodingModelState.RunScope.IDLE),
                "",
                CodingModelControls.unavailable(),
                CodingModelPreferences.recommended(),
                Optional.empty());
        CodingSessionView sessionView = new CodingSessionView(
                summary,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                "sha256:test",
                "cli-coding@1.0.0",
                new CodingModelSelection(reauthOption, 0, true),
                Optional.empty());

        TerminalUiState state = reducer.reduce(
                TerminalUiState.initial(120, 40), new TerminalUiAction.SessionLoaded(sessionView, List.of()));

        assertThat(state.footer().model()).contains("Claude 3.7 Sonnet");
        assertThat(state.footer().model()).endsWith(" · [登录已失效，请重新认证]");
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
