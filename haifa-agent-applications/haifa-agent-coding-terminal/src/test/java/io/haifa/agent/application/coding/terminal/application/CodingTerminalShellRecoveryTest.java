package io.haifa.agent.application.coding.terminal.application;

import static io.haifa.agent.application.coding.terminal.application.CodingTerminalTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.application.coding.terminal.event.TerminalEventPump;
import io.haifa.agent.application.coding.terminal.event.TerminalInput;
import io.haifa.agent.application.coding.terminal.event.TerminalUiAction;
import io.haifa.agent.application.coding.terminal.state.TerminalUiReducer;
import io.haifa.agent.application.coding.terminal.state.TerminalUiState;
import io.haifa.agent.application.project.product.coding.CodingShellExecution;
import io.haifa.agent.application.project.product.coding.CodingShellResult;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.runtime.api.RunEventPayloads;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class CodingTerminalShellRecoveryTest {
    private static CodingShellExecution receipt(boolean cancel, boolean complete) {
        return new CodingShellExecution("shell-token", SESSION_ID, 0,
                complete ? CodingShellExecution.State.COMPLETED : CodingShellExecution.State.RUNNING,
                cancel, complete ? Optional.of(new CodingShellResult("EXITED", Optional.of(0),
                        "recovered output", Optional.empty(), false, false)) : Optional.empty(), Optional.empty());
    }

    @Test
    void shellAdmissionAndCancellationStayOffUiAndCancellationUsesControlLane() throws Exception {
        BlockingCall admission = new BlockingCall();
        BlockingCall cancellation = new BlockingCall();
        AtomicBoolean cancelled = new AtomicBoolean();
        FakeClient client = new FakeClient(view(Optional.empty())) {
            @Override public boolean recoverableShell() { return true; }
            @Override public CodingShellExecution startShell(String token, boolean approved) {
                admission.block(); return receipt(cancelled.get(), false);
            }
            @Override public CodingShellExecution cancelShell(AgentSessionId session, String id) {
                cancellation.block(); cancelled.set(true); return receipt(true, false);
            }
        };
        var interactive = new ArrayDeque<Runnable>();
        var controls = new ArrayDeque<Runnable>();
        var maintenance = new ArrayDeque<Runnable>();
        var pump = new TerminalEventPump(32);
        var controller = new CodingTerminalController(PROJECT_ID, client, pump, new TerminalUiReducer(),
                TerminalUiState.initial(120, 40), controls::add, interactive::add, maintenance::add);
        controller.open(SESSION_ID);
        controller.accept(input(TerminalInput.Kind.SUBMIT, "!!echo fixture"));
        interactive.remove().run(); controller.drainEvents();
        Thread start = new Thread(interactive.remove()); start.start();
        Thread cancel = null;
        try {
            assertThat(admission.awaitStarted()).isTrue();
            assertThat(controller.state().status()).contains("Starting Shell", "does not cancel");
            controller.accept(input(TerminalInput.Kind.CANCEL_OR_CLOSE, ""));
            controller.accept(input(TerminalInput.Kind.EXECUTE_COMMAND, "/shell cancel shell-token"));
            assertThat(controller.state().status()).contains("Cancelling Shell");
            cancel = new Thread(controls.remove()); cancel.start();
            assertThat(cancellation.awaitStarted()).isTrue();
            pump.offer(new TerminalUiAction.TerminalResized(90, 25));
            pump.offer(new TerminalUiAction.RunEventReceived(event(1,
                    new RunEventPayloads.InteractionLifecycle("interaction-1", "APPROVAL", "APPLIED", "safe"))));
            controller.accept(input(TerminalInput.Kind.INTERRUPT, ""));
            controller.drainEvents();
            assertThat(controller.state().columns()).isEqualTo(90);
            assertThat(client.cancelledRuns).isEmpty();
        } finally {
            admission.release(); cancellation.release(); start.join(5000); if (cancel != null) cancel.join(5000);
            controller.close();
        }
    }

    @Test
    void reattachedClientListsAndQueriesRetainedOutputWithoutStartingAgainOrDuplicatingCards() {
        FakeClient client = new FakeClient(view(Optional.empty())) {
            @Override public List<CodingShellExecution> shellExecutions(AgentSessionId session) {
                return List.of(receipt(false, true));
            }
            @Override public CodingShellExecution shellExecution(AgentSessionId session, String id) {
                return receipt(false, true);
            }
            @Override public CodingShellExecution startShell(String token, boolean approved) {
                throw new AssertionError("query must not replay a command");
            }
        };
        try (var controller = controller(client)) {
            controller.open(SESSION_ID);
            controller.accept(input(TerminalInput.Kind.EXECUTE_COMMAND, "/shell")); controller.drainEvents();
            assertThat(controller.state().transcript()).anySatisfy(item ->
                    assertThat(item.body()).contains("shell-token", "COMPLETED"));
            for (int i = 0; i < 2; i++) {
                controller.accept(input(TerminalInput.Kind.EXECUTE_COMMAND, "/shell shell-token"));
                controller.drainEvents();
            }
            assertThat(controller.state().transcript().stream().filter(item -> item.id().equals("shell-shell-token")))
                    .hasSize(1).allSatisfy(item -> assertThat(item.body()).contains("recovered output"));
        }
    }
}
