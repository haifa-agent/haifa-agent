package io.haifa.agent.application.coding.terminal.application;

import static io.haifa.agent.application.coding.terminal.application.CodingTerminalTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.application.project.product.coding.CodingSessionView;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.RunEventPayloads;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The Host advances a persisted follow-up into the next Run even while no UI is attached, and the per-Run event
 * stream of the settled Run can stay open. These cases pin the bounded handoff: one reconcile per settled Run, the
 * successor becomes the observed Run, and a stale Run1 event can never contaminate Run2.
 */
class CodingTerminalFollowUpHandoffTest {
    @Test
    void aSettledRunTransfersTheSelectionToTheQueuedSuccessor() {
        FakeClient client = new FakeClient(activeView());
        client.restorable = queuedFollowUp();
        var controller = controller(client);
        controller.open(SESSION_ID);

        // Run2 is what the Host already advanced to while the terminal was observing Run1.
        client.reconciledView = runningView(new AgentRunId("run-2"), 2);
        client.emit(event(1, new RunEventPayloads.RunLifecycle("COMPLETED", 2, "RUN_COMPLETED")));
        controller.drainEvents();

        assertThat(client.reconcileCalls).isEqualTo(1);
        assertThat(controller.state().currentRunId()).contains(new AgentRunId("run-2"));
        assertThat(controller
                        .state()
                        .session()
                        .flatMap(CodingSessionView::activeRun)
                        .map(AgentRunSnapshot::runId))
                .contains(new AgentRunId("run-2"));
    }

    @Test
    void theHandoffAsksTheHostOnlyOncePerSettledRun() {
        FakeClient client = new FakeClient(activeView());
        client.restorable = queuedFollowUp();
        var controller = controller(client);
        controller.open(SESSION_ID);

        client.reconciledView = runningView(new AgentRunId("run-2"), 2);
        client.emit(event(1, new RunEventPayloads.RunLifecycle("COMPLETED", 2, "RUN_COMPLETED")));
        controller.drainEvents();
        client.emit(event(2, new RunEventPayloads.RunLifecycle("COMPLETED", 3, "RUN_COMPLETED")));
        controller.drainEvents();

        assertThat(client.reconcileCalls).isEqualTo(1);
    }

    @Test
    void ordinaryDeltasNeverTriggerAHandoffReconcile() {
        FakeClient client = new FakeClient(activeView());
        client.restorable = queuedFollowUp();
        var controller = controller(client);
        controller.open(SESSION_ID);

        client.emit(event(1, new RunEventPayloads.AssistantTextDelta("generation-1", "still working")));
        client.emit(event(2, new RunEventPayloads.AssistantTextDelta("generation-1", " and working")));
        controller.drainEvents();

        assertThat(client.reconcileCalls).isZero();
        assertThat(controller.state().currentRunId()).contains(new AgentRunId("run-1"));
    }

    @Test
    void aStaleRunOneEventCannotContaminateTheSelectedRunTwo() {
        FakeClient client = new FakeClient(activeView());
        client.restorable = queuedFollowUp();
        var controller = controller(client);
        controller.open(SESSION_ID);

        client.reconciledView = runningView(new AgentRunId("run-2"), 2);
        io.haifa.agent.runtime.api.AgentRunEventListener runOneListener = client.lastListener;
        client.emit(event(1, new RunEventPayloads.RunLifecycle("COMPLETED", 2, "RUN_COMPLETED")));
        controller.drainEvents();
        assertThat(controller.state().currentRunId()).contains(new AgentRunId("run-2"));

        // The old Run1 stream is still open on the Host, so a late Run1 delta arrives after the handoff.
        runOneListener.onEvent(event(2, new RunEventPayloads.AssistantTextDelta("generation-1", "late run-1 text")));
        controller.drainEvents();

        assertThat(controller.state().transcript())
                .noneMatch(item -> item.body().contains("late run-1 text"));
        assertThat(controller.state().currentRunId()).contains(new AgentRunId("run-2"));
    }

    @Test
    void aSettledRunWithoutAQueuedSuccessorLoadsNothing() {
        FakeClient client = new FakeClient(activeView());
        var controller = controller(client);
        controller.open(SESSION_ID);

        client.emit(event(1, new RunEventPayloads.RunLifecycle("COMPLETED", 2, "RUN_COMPLETED")));
        controller.drainEvents();

        assertThat(client.reconcileCalls).isZero();
    }

    private static java.util.List<io.haifa.agent.application.project.product.coding.CodingQueuedMessage>
            queuedFollowUp() {
        return java.util.List.of(new io.haifa.agent.application.project.product.coding.CodingQueuedMessage(
                "follow-1", SESSION_ID, "queued successor task", 1, 2));
    }

    private static CodingSessionView runningView(AgentRunId runId, long revision) {
        return new CodingSessionView(
                new io.haifa.agent.application.project.product.coding.CodingSessionSummary(
                        SESSION_ID,
                        PROJECT_ID,
                        "session",
                        io.haifa.agent.core.session.AgentSessionStatus.ACTIVE,
                        Optional.of(runId),
                        Optional.of(AgentRunStatus.RUNNING),
                        0,
                        Instant.EPOCH,
                        revision),
                Optional.of(new AgentRunSnapshot(
                        runId,
                        AgentRunStatus.RUNNING,
                        1,
                        Instant.EPOCH,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty())),
                Optional.empty(),
                Optional.empty(),
                "sha256:configuration",
                "cli-coding@1.0.0",
                new io.haifa.agent.application.project.product.coding.CodingModelSelection(
                        model("cli-coding@1.0.0", "cli-coding@1.0.0"), revision, true),
                Optional.empty());
    }
}
