package io.haifa.agent.application.coding.terminal.tui4j;

import static org.assertj.core.api.Assertions.assertThat;

import com.williamcallahan.tui4j.compat.bubbletea.Command;
import io.haifa.agent.application.coding.terminal.event.TerminalInput;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ComposerInputTest {
    @Test
    void keepsOneIdleCheckWhileFragmentsMoveItsDeadline() {
        AtomicLong clock = new AtomicLong();
        ComposerInput input = new ComposerInput(clock::get);
        input.beginBatch(0, 0, clock.get());
        var first = (ComposerInput.IdleCheck) input.scheduleIdle();

        clock.addAndGet(Duration.ofMillis(50).toNanos());
        assertThat(Command.isNone(input.scheduleIdle())).isTrue();
        clock.addAndGet(Duration.ofMillis(50).toNanos());
        var early = input.checkIdle(first.message());

        assertThat(early.settled()).isFalse();
        var remaining = (ComposerInput.IdleCheck) early.command();
        assertThat(remaining.delay()).isEqualTo(Duration.ofMillis(50));

        clock.addAndGet(Duration.ofMillis(50).toNanos());
        assertThat(input.checkIdle(remaining.message()).settled()).isTrue();
    }

    @Test
    void anOldCheckCannotSettleTheNextBatch() {
        AtomicLong clock = new AtomicLong();
        ComposerInput input = new ComposerInput(clock::get);
        input.beginBatch(0, 0, clock.get());
        var old = (ComposerInput.IdleCheck) input.scheduleIdle();
        input.finishBatch();
        input.beginBatch(3, 3, clock.get());
        var next = (ComposerInput.IdleCheck) input.scheduleIdle();
        clock.addAndGet(StreamedInputBatch.IDLE_INTERVAL.toNanos());

        assertThat(input.checkIdle(old.message()).settled()).isFalse();
        assertThat(input.batch().start()).isEqualTo(3);
        assertThat(input.checkIdle(next.message()).settled()).isTrue();
    }

    @Test
    void idleCompletionDoesNotConsumeAnEnterStillWaitingForContinuation() {
        AtomicLong clock = new AtomicLong();
        ComposerInput input = new ComposerInput(clock::get);
        input.beginBatch(2, 4, clock.get());
        var idle = (ComposerInput.IdleCheck) input.scheduleIdle();
        input.deferEnter(TerminalInput.Kind.SUBMIT, 4);
        clock.addAndGet(StreamedInputBatch.IDLE_INTERVAL.toNanos());

        var result = input.checkIdle(idle.message());

        assertThat(result.settled()).isFalse();
        assertThat(Command.isNone(result.command())).isTrue();
        assertThat(input.takeEnter().start()).isEqualTo(2);
        assertThat(input.pendingEnter()).isNull();
    }

    @Test
    void aConsumedEnterCannotBeCommittedByItsOldMessage() {
        ComposerInput input = new ComposerInput(() -> 0);
        input.deferEnter(TerminalInput.Kind.SUBMIT, 7);
        var entered = input.takeEnter();

        assertThat(input.consumeEnter(new ComposerInput.EnterMessage(entered.sequence())))
                .isNull();
    }
}
