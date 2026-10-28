package io.haifa.agent.application.coding.terminal.application;

import static io.haifa.agent.application.coding.terminal.application.CodingTerminalTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.application.coding.terminal.event.TerminalEventPump;
import io.haifa.agent.application.coding.terminal.event.TerminalInput;
import io.haifa.agent.application.coding.terminal.event.TerminalUiAction;
import io.haifa.agent.application.coding.terminal.state.TerminalUiReducer;
import io.haifa.agent.application.coding.terminal.state.TerminalUiState;
import io.haifa.agent.application.coding.terminal.state.WaitingMessage;
import io.haifa.agent.application.project.product.coding.CodingSessionSummary;
import io.haifa.agent.application.project.product.coding.CodingSessionView;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.runtime.api.RunEventPayloads;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CodingTerminalWaitingTest {
    private static final AgentSessionId OTHER_SESSION_ID = new AgentSessionId("session-2");

    @Test
    void activeEnterQueuesLocalWaitingWithoutSteering() {
        FakeClient client = new FakeClient(activeView());
        var controller = controller(client);
        controller.open(SESSION_ID);

        controller.accept(input(TerminalInput.Kind.SUBMIT, "先别改 assertion，只修 checkpoint recovery 路径。"));

        assertThat(client.steeredMessages).isEmpty();
        assertThat(client.submittedMessages).isEmpty();
        assertThat(controller.state().waiting()).hasValueSatisfying(waiting -> {
            assertThat(waiting.phase()).isEqualTo(WaitingMessage.Phase.WAITING);
            assertThat(waiting.text()).contains("checkpoint recovery");
        });
        assertThat(controller.state().editorBuffer()).isEmpty();
        assertThat(controller.state().currentRunId()).isPresent();
        assertThat(controller.state().cancelArmed()).isFalse();
    }

    @Test
    void escapeCancelsWaitingWithoutArmingRunInterrupt() {
        FakeClient client = new FakeClient(activeView());
        var controller = controller(client);
        controller.open(SESSION_ID);
        controller.accept(input(TerminalInput.Kind.SUBMIT, "hold this follow-up"));

        controller.accept(input(TerminalInput.Kind.CANCEL_OR_CLOSE, ""));

        assertThat(controller.state().waiting()).isEmpty();
        assertThat(controller.state().cancelArmed()).isFalse();
        assertThat(client.cancelledSessions).isEmpty();
        assertThat(controller.state().currentRunId()).isPresent();
        assertThat(client.steeredMessages).isEmpty();
    }

    @Test
    void emptyEnterSendsWaitingAsSteerNow() {
        FakeClient client = new FakeClient(activeView());
        var controller = controller(client);
        controller.open(SESSION_ID);
        controller.accept(input(TerminalInput.Kind.SUBMIT, "redirect the current task"));

        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));

        assertThat(client.steeredMessages).containsExactly("redirect the current task");
        assertThat(controller.state().waiting())
                .hasValueSatisfying(waiting -> assertThat(waiting.phase()).isEqualTo(WaitingMessage.Phase.SENDING));
    }

    @Test
    void editThenSaveKeepsWaitingWithoutSteering() {
        FakeClient client = new FakeClient(activeView());
        var controller = controller(client);
        controller.open(SESSION_ID);
        controller.accept(input(TerminalInput.Kind.SUBMIT, "original waiting text"));

        controller.accept(input(TerminalInput.Kind.EDIT_WAITING, ""));
        assertThat(controller.state().waiting())
                .hasValueSatisfying(waiting -> assertThat(waiting.phase()).isEqualTo(WaitingMessage.Phase.EDITING));
        assertThat(controller.state().editorBuffer()).isEqualTo("original waiting text");

        controller.accept(input(TerminalInput.Kind.SUBMIT, "edited waiting text"));

        assertThat(client.steeredMessages).isEmpty();
        assertThat(controller.state().waiting()).hasValueSatisfying(waiting -> {
            assertThat(waiting.phase()).isEqualTo(WaitingMessage.Phase.WAITING);
            assertThat(waiting.text()).isEqualTo("edited waiting text");
        });
        assertThat(controller.state().editorBuffer()).isEmpty();
    }

    @Test
    void followUpStillBypassesWaitingQueue() {
        FakeClient client = new FakeClient(activeView());
        var controller = controller(client);
        controller.open(SESSION_ID);

        controller.accept(input(TerminalInput.Kind.FOLLOW_UP, "durable follow-up"));

        assertThat(client.steeredMessages).isEmpty();
        assertThat(controller.state().waiting()).isEmpty();
    }

    @Test
    void switchingSessionClearsWaitingWithoutSubmittingIntoTheOtherSession() {
        FakeClient client = new FakeClient(activeView());
        var pump = new TerminalEventPump(32);
        var controller = new CodingTerminalController(
                PROJECT_ID, client, pump, new TerminalUiReducer(), TerminalUiState.initial(120, 40), Runnable::run);
        controller.open(SESSION_ID);
        controller.accept(input(TerminalInput.Kind.SUBMIT, "queued for session A"));

        pump.offer(new TerminalUiAction.SessionLoaded(idleOtherSession(), List.of()));
        controller.drainEvents();

        assertThat(controller.state().waiting()).isEmpty();
        assertThat(client.steeredMessages).isEmpty();
        assertThat(client.submittedMessages).isEmpty();
        assertThat(controller.state().session().orElseThrow().summary().sessionId())
                .isEqualTo(OTHER_SESSION_ID);
    }

    @Test
    void newDuringActiveRunCreatesSessionOnFirstMessageInsteadOfQueueingWaiting() {
        FakeClient client = new FakeClient(activeView());
        var controller = controller(client);
        controller.open(SESSION_ID);
        controller.accept(input(TerminalInput.Kind.SUBMIT, "queued before /new"));
        assertThat(controller.state().waiting()).isPresent();

        controller.accept(input(TerminalInput.Kind.SUBMIT, "/new"));
        assertThat(controller.state().waiting()).isEmpty();

        client.view = view(Optional.empty());
        client.reconciledView = view(Optional.empty());
        int attemptsBeforeCreate = client.submitAttempts;
        controller.accept(input(TerminalInput.Kind.SUBMIT, "first message of the new session"));

        assertThat(controller.state().waiting()).isEmpty();
        assertThat(client.submitAttempts).isEqualTo(attemptsBeforeCreate + 1);
        assertThat(client.steeredMessages).isEmpty();
    }

    @Test
    void visiblePastePlaceholderQueuesExpandedTextWithoutSlashRouting() {
        FakeClient client = new FakeClient(activeView());
        var controller = controller(client);
        controller.open(SESSION_ID);

        controller.accept(input(TerminalInput.Kind.SUBMIT, "/new\nthen continue the task"), "[Pasted #1 ~2 lines]");

        assertThat(controller.state().waiting()).hasValueSatisfying(waiting -> {
            assertThat(waiting.phase()).isEqualTo(WaitingMessage.Phase.WAITING);
            assertThat(waiting.text()).startsWith("/new");
        });
        assertThat(client.submittedMessages).isEmpty();
        assertThat(controller.state().status()).isNotEqualTo("New session: enter the first message");
    }

    @Test
    void autoSubmitAfterRunPreservesUnrelatedEditorDraft() {
        FakeClient client = new FakeClient(activeView());
        var pump = new TerminalEventPump(32);
        var controller = new CodingTerminalController(
                PROJECT_ID, client, pump, new TerminalUiReducer(), TerminalUiState.initial(120, 40), Runnable::run);
        controller.open(SESSION_ID);
        controller.accept(input(TerminalInput.Kind.SUBMIT, "send after run"));
        String draft = "next unrelated draft";
        controller.accept(new TerminalInput(TerminalInput.Kind.EDITOR_CHANGED, draft, draft.length()));

        pump.offer(new TerminalUiAction.RunEventReceived(
                event(1, new RunEventPayloads.RunLifecycle("COMPLETED", 2, "NONE"))));
        controller.drainEvents();

        assertThat(controller.state().waiting()).isEmpty();
        assertThat(client.submittedMessages).containsExactly("send after run");
        assertThat(controller.state().editorBuffer()).isEqualTo(draft);
    }

    @Test
    void autoSubmitAfterRunUsesEditedTextWhileEditing() {
        FakeClient client = new FakeClient(activeView());
        var pump = new TerminalEventPump(32);
        var controller = new CodingTerminalController(
                PROJECT_ID, client, pump, new TerminalUiReducer(), TerminalUiState.initial(120, 40), Runnable::run);
        controller.open(SESSION_ID);
        controller.accept(input(TerminalInput.Kind.SUBMIT, "original waiting text"));
        controller.accept(input(TerminalInput.Kind.EDIT_WAITING, ""));
        String revised = "revised while run finishes";
        controller.accept(new TerminalInput(TerminalInput.Kind.EDITOR_CHANGED, revised, revised.length()));

        pump.offer(new TerminalUiAction.RunEventReceived(
                event(1, new RunEventPayloads.RunLifecycle("COMPLETED", 2, "NONE"))));
        controller.drainEvents();

        assertThat(controller.state().waiting()).isEmpty();
        assertThat(client.submittedMessages).containsExactly(revised);
        assertThat(controller.state().editorBuffer()).isEmpty();
    }

    @Test
    void deferredAutoSubmitRetriesAfterSelectorCloses() {
        FakeClient client = new FakeClient(activeView());
        var pump = new TerminalEventPump(32);
        var controller = new CodingTerminalController(
                PROJECT_ID, client, pump, new TerminalUiReducer(), TerminalUiState.initial(120, 40), Runnable::run);
        controller.open(SESSION_ID);
        controller.accept(input(TerminalInput.Kind.SUBMIT, "flush after modal"));
        pump.offer(new TerminalUiAction.SelectorOpened(
                new io.haifa.agent.application.coding.terminal.state.TerminalSelector(
                        "model", "Model", java.util.List.of("a", "b"), 1)));
        controller.drainEvents();

        pump.offer(new TerminalUiAction.RunEventReceived(
                event(1, new RunEventPayloads.RunLifecycle("COMPLETED", 2, "NONE"))));
        controller.drainEvents();
        assertThat(controller.state().waiting()).isPresent();
        assertThat(client.submittedMessages).isEmpty();

        controller.accept(input(TerminalInput.Kind.CANCEL_OR_CLOSE, ""));

        assertThat(controller.state().selector()).isEmpty();
        assertThat(controller.state().waiting()).isEmpty();
        assertThat(client.submittedMessages).containsExactly("flush after modal");
    }

    @Test
    void sendWaitingNowStaysResponsiveWhileClientIsBlocked() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var effects = new ArrayDeque<Runnable>();
        FakeClient client = new FakeClient(activeView()) {
            @Override
            public void steer(AgentSessionId sessionId, AgentRunId activeRunId, String message, String idempotencyKey) {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                super.steer(sessionId, activeRunId, message, idempotencyKey);
            }
        };
        var pump = new TerminalEventPump(32);
        var controller = new CodingTerminalController(
                PROJECT_ID, client, pump, new TerminalUiReducer(), TerminalUiState.initial(120, 40), effects::add);
        controller.open(SESSION_ID);
        controller.accept(input(TerminalInput.Kind.SUBMIT, "blocked send-now"));

        controller.accept(input(TerminalInput.Kind.SUBMIT, ""));
        assertThat(effects).hasSize(1);
        assertThat(controller.state().waiting())
                .hasValueSatisfying(waiting -> assertThat(waiting.phase()).isEqualTo(WaitingMessage.Phase.SENDING));
        assertThat(controller.state().status()).isEqualTo("Submitting");

        var worker = new Thread(effects.remove());
        worker.start();
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            pump.offer(new TerminalUiAction.TerminalResized(90, 25));
            controller.drainEvents();
            controller.accept(input(TerminalInput.Kind.CANCEL_OR_CLOSE, ""));
            assertThat(controller.state().columns()).isEqualTo(90);
            assertThat(controller.state().cancelArmed()).isFalse();
        } finally {
            release.countDown();
            worker.join(5_000);
        }
        controller.drainEvents();
        assertThat(client.steeredMessages).containsExactly("blocked send-now");
    }

    private static CodingSessionView idleOtherSession() {
        return new CodingSessionView(
                new CodingSessionSummary(
                        OTHER_SESSION_ID,
                        PROJECT_ID,
                        "other",
                        io.haifa.agent.core.session.AgentSessionStatus.ACTIVE,
                        Optional.empty(),
                        Optional.empty(),
                        0,
                        Instant.EPOCH,
                        0),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                "sha256:configuration",
                "cli-coding@1.0.0",
                new io.haifa.agent.application.project.product.coding.CodingModelSelection(
                        model("cli-coding@1.0.0", "cli-coding@1.0.0"), 0, true));
    }
}
