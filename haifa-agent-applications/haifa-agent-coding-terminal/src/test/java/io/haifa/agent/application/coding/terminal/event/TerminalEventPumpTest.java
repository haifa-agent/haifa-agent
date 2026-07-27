package io.haifa.agent.application.coding.terminal.event;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TerminalEventPumpTest {
    @Test
    void isBoundedAndLeavesRejectedEventsReplayable() {
        TerminalEventPump pump = new TerminalEventPump(1);
        var first = new TerminalUiAction.StatusChanged("Working");
        var second = new TerminalUiAction.RecoverableFailure("ERROR");

        assertThat(pump.offer(first)).isTrue();
        assertThat(pump.offer(second)).isFalse();
        assertThat(pump.drain(10)).containsExactly(first);
        assertThat(pump.pendingCount()).isZero();
    }
}
