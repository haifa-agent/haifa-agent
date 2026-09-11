package io.haifa.agent.application.coding.terminal.application;

import static io.haifa.agent.application.coding.terminal.application.CodingTerminalTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.application.coding.terminal.event.TerminalEventPump;
import io.haifa.agent.application.coding.terminal.event.TerminalInput;
import io.haifa.agent.application.coding.terminal.event.TerminalUiAction;
import io.haifa.agent.application.coding.terminal.state.TerminalUiReducer;
import io.haifa.agent.application.coding.terminal.state.TerminalUiState;
import io.haifa.agent.application.project.product.ProjectProductException;
import io.haifa.agent.application.project.product.coding.CodingShellPlan;
import io.haifa.agent.runtime.api.RunEventPayloads;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class CodingTerminalExecutionTest {
    @Test
    void secondTurnReconcilesAndRetriesWhenTheRunSettlesDuringSubmission() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        client.submitFailure = new ProjectProductException("CODING_SESSION_ACTIVE", "Run became active");
        var controller = controller(client);
        controller.open(SESSION_ID);

        controller.accept(input(TerminalInput.Kind.SUBMIT, "second turn"));

        assertThat(client.submitAttempts).isEqualTo(2);
        assertThat(client.submittedMessages).containsExactly("second turn");
        assertThat(controller.state().editorBuffer()).isEmpty();
        assertThat(controller.state().transcript()).anyMatch(item -> item.body().equals("second turn"));
    }

    @Test
    void terminalRunEventRoutesTheNextEnterToANewTurnInsteadOfStaleSteer() {
        TerminalUiReducer reducer = new TerminalUiReducer();
        TerminalUiState active = reducer.reduce(
                TerminalUiState.initial(120, 40),
                new TerminalUiAction.SessionLoaded(activeView(), List.of("Loaded resources: project")));
        TerminalUiState settled = reducer.reduce(
                active,
                new TerminalUiAction.RunEventReceived(
                        event(1, new RunEventPayloads.RunLifecycle("COMPLETED", 2, "NONE"))));
        FakeClient client = new FakeClient(view(Optional.empty()));
        var controller = new CodingTerminalController(
                PROJECT_ID, client, new TerminalEventPump(32), reducer, settled, Runnable::run);

        controller.accept(input(TerminalInput.Kind.SUBMIT, "next turn"));

        assertThat(client.submittedMessages).containsExactly("next turn");
        assertThat(client.steeredMessages).isEmpty();
        assertThat(controller.state().editorBuffer()).isEmpty();
    }

    @Test
    void revisionMutationReconcilesTheAuthoritativeSessionBeforeRename() {
        FakeClient client = new FakeClient(view(Optional.empty(), 0, "session"));
        client.reconciledView = view(Optional.empty(), 7, "session");
        var controller = controller(client);
        controller.open(SESSION_ID);

        controller.accept(input(TerminalInput.Kind.SUBMIT, "/rename reconciled-name"));

        assertThat(client.renamedExpectedRevision).isEqualTo(7);
        assertThat(client.renamedDisplayName).isEqualTo("reconciled-name");
        assertThat(controller.state().session().orElseThrow().summary().displayName())
                .isEqualTo("reconciled-name");
    }

    @Test
    void productFailureStaysInTheTerminalAndPreservesTheDraft() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        client.submitFailure = new ProjectProductException("SESSION_NOT_FOUND", "Session unavailable");
        var controller = controller(client);
        controller.open(SESSION_ID);

        controller.accept(input(TerminalInput.Kind.SUBMIT, "keep this draft"));

        assertThat(controller.state().recoverableError()).contains("SESSION_NOT_FOUND");
        assertThat(controller.state().editorBuffer()).isEqualTo("keep this draft");
        assertThat(controller.state().editorCursor()).isEqualTo("keep this draft".length());
    }

    @Test
    void escapeCancelsAnActiveRunEvenWhenASelectorIsOpen() {
        FakeClient client = new FakeClient(activeView());
        TerminalUiReducer reducer = new TerminalUiReducer();
        TerminalUiState activeState = reducer.reduce(
                TerminalUiState.initial(120, 40),
                new TerminalUiAction.SessionLoaded(activeView(), List.of("Loaded resources: project")));
        activeState = reducer.reduce(activeState, new TerminalUiAction.EditorChanged("draft", 5));
        activeState = reducer.reduce(
                activeState,
                new TerminalUiAction.SelectorOpened(
                        new io.haifa.agent.application.coding.terminal.state.TerminalSelector(
                                "completion", "Commands", List.of("/resume"), 0)));
        var controller = new CodingTerminalController(
                PROJECT_ID, client, new TerminalEventPump(32), reducer, activeState, Runnable::run);

        controller.accept(input(TerminalInput.Kind.CANCEL_OR_CLOSE, "draft"));

        assertThat(client.cancelledSessions).containsExactly(SESSION_ID);
        assertThat(controller.state().status()).isEqualTo("Cancelling");
        assertThat(controller.state().selector()).isEmpty();
        assertThat(controller.state().editorBuffer()).isEqualTo("draft");
    }

    @Test
    void activeRunFooterShowsTheCurrentRunTaskRatherThanTheSessionName() {
        TerminalUiState state = new TerminalUiReducer()
                .reduce(TerminalUiState.initial(120, 40), new TerminalUiAction.SessionLoaded(activeView(), List.of()));

        assertThat(state.footer().runStatus()).isEqualTo("RUNNING");
        assertThat(state.footer().session()).isEqualTo("task: latest active task");
    }

    @Test
    void escapeReconcilesAStaleIdleViewBeforeCancellingTheActiveRun() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        client.reconciledView = activeView();
        var controller = controller(client);
        controller.open(SESSION_ID);

        controller.accept(input(TerminalInput.Kind.CANCEL_OR_CLOSE, ""));

        assertThat(client.cancelledSessions).containsExactly(SESSION_ID);
        assertThat(controller.state().status()).isEqualTo("Cancelling");
    }

    @Test
    void closedEventSubscriptionReconcilesAndClearsTheStaleWorkingState() {
        FakeClient client = new FakeClient(activeView());
        client.reconciledView = view(Optional.empty());
        var controller = controller(client);
        controller.open(SESSION_ID);
        client.lastSubscription.close();

        controller.drainEvents();

        assertThat(client.reconcileCalls).isEqualTo(1);
        assertThat(client.subscriptionCount).isEqualTo(1);
        assertThat(controller.state().currentRunId()).isEmpty();
        assertThat(controller.state().status()).isEqualTo("Idle");
    }

    @Test
    void eventQueueOverflowReconcilesFromTheAuthoritativeSessionView() {
        FakeClient client = new FakeClient(activeView());
        client.reconciledView = view(Optional.empty());
        TerminalEventPump pump = new TerminalEventPump(1);
        var controller = new CodingTerminalController(
                PROJECT_ID, client, pump, new TerminalUiReducer(), TerminalUiState.initial(120, 40), Runnable::run);
        controller.open(SESSION_ID);
        assertThat(pump.offer(new TerminalUiAction.StatusChanged("first"))).isTrue();
        assertThat(pump.offer(new TerminalUiAction.StatusChanged("dropped"))).isFalse();

        controller.drainEvents();

        assertThat(client.reconcileCalls).isEqualTo(1);
        assertThat(controller.state().currentRunId()).isEmpty();
        assertThat(controller.state().status()).isEqualTo("Idle");
    }

    @Test
    void transientCursorPersistenceFailureDoesNotStopOutputAndRetriesOnTheNextTick() {
        FakeClient client = new FakeClient(activeView());
        client.acknowledgementFailuresRemaining = 1;
        var controller = controller(client);
        controller.open(SESSION_ID);
        client.emit(event(1, new RunEventPayloads.AssistantTextDelta("generation-1", "keeps rendering")));
        client.emit(event(2, new RunEventPayloads.AssistantTextDelta("generation-1", " after contention")));

        controller.drainEvents();

        assertThat(controller.state().transcript())
                .anyMatch(item -> item.body().contains("keeps rendering after contention"));
        assertThat(client.acknowledgementCalls).isEqualTo(1);

        controller.drainEvents();

        assertThat(client.acknowledgementCalls).isEqualTo(2);
        assertThat(client.acknowledgedCursor.exclusiveSequence()).isEqualTo(OptionalLong.of(2));
    }

    @Test
    void governedShellApprovalCompletesThroughTheClientAndProjectsSafeResult() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        client.shellState = CodingShellPlan.State.APPROVAL_REQUIRED;
        var controller = controller(client);
        controller.open(SESSION_ID);

        controller.accept(input(TerminalInput.Kind.SUBMIT, "!git status --short"));
        assertThat(controller.state().selector()).isPresent();
        controller.accept(input(TerminalInput.Kind.SELECT_PREVIOUS, ""));
        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));

        assertThat(client.shellApproved).isTrue();
        assertThat(client.shellIncludedInContext).isTrue();
        assertThat(controller.state().transcript())
                .anyMatch(item ->
                        item.kind() == io.haifa.agent.application.coding.terminal.state.TranscriptItem.Kind.EXECUTION
                                && item.body().contains("safe shell output")
                                && item.expanded());
    }

    @Test
    void doubleBangExecutesButDoesNotAppendToModelContext() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        client.shellState = CodingShellPlan.State.READY;
        var controller = controller(client);
        controller.open(SESSION_ID);

        controller.accept(input(TerminalInput.Kind.SUBMIT, "!!git status --short"));

        assertThat(client.shellIncludedInContext).isFalse();
        assertThat(controller.state().status()).contains("excluded from model context");
    }

    @Test
    void closingShellApprovalSelectorDiscardsThePendingRequest() {
        FakeClient client = new FakeClient(view(Optional.empty()));
        client.shellState = CodingShellPlan.State.APPROVAL_REQUIRED;
        var controller = controller(client);
        controller.open(SESSION_ID);

        controller.accept(input(TerminalInput.Kind.SUBMIT, "!git status --short"));
        controller.accept(input(TerminalInput.Kind.CANCEL_OR_CLOSE, ""));

        assertThat(client.shellDiscarded).isTrue();
        assertThat(controller.state().selector()).isEmpty();
    }
}
