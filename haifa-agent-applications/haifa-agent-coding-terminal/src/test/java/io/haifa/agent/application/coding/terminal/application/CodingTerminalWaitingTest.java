package io.haifa.agent.application.coding.terminal.application;

import static io.haifa.agent.application.coding.terminal.application.CodingTerminalTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.application.coding.terminal.event.TerminalInput;
import io.haifa.agent.application.coding.terminal.state.WaitingMessage;
import org.junit.jupiter.api.Test;

class CodingTerminalWaitingTest {
    @Test
    void activeEnterQueuesLocalWaitingWithoutSteering() {
        FakeClient client = new FakeClient(activeView());
        var controller = controller(client);
        controller.open(SESSION_ID);

        controller.accept(input(TerminalInput.Kind.SUBMIT, "先别改 assertion，只修 checkpoint recovery 路径。"));

        assertThat(client.steeredMessages).isEmpty();
        assertThat(client.submittedMessages).isEmpty();
        assertThat(controller.state().waiting())
                .hasValueSatisfying(waiting -> {
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
        assertThat(controller.state().waiting())
                .hasValueSatisfying(waiting -> {
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
}
