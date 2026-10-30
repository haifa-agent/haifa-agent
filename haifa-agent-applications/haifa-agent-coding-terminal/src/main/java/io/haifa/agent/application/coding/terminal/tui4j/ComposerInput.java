package io.haifa.agent.application.coding.terminal.tui4j;

import com.williamcallahan.tui4j.compat.bubbletea.Command;
import com.williamcallahan.tui4j.compat.bubbletea.Message;
import io.haifa.agent.application.coding.terminal.event.TerminalInput;
import java.time.Duration;
import java.util.function.LongSupplier;

/** UI-thread ownership of a composer's input batch, deferred Enter and one outstanding idle check. */
final class ComposerInput {
    private final LongSupplier clock;
    private StreamedInputBatch batch;
    private DeferredEnter enter;
    private long enterSequence;
    private long idleSequence;
    private long idleDeadline;
    private boolean idleScheduled;

    ComposerInput(LongSupplier clock) {
        this.clock = clock;
    }

    StreamedInputBatch batch() {
        return batch;
    }

    void beginBatch(int start, int end, long now) {
        batch = StreamedInputBatch.begin(start, end, now);
    }

    StreamedInputBatch finishBatch() {
        StreamedInputBatch finished = batch;
        batch = null;
        enter = null;
        idleScheduled = false;
        idleSequence++;
        return finished;
    }

    DeferredEnter pendingEnter() {
        return enter;
    }

    DeferredEnter takeEnter() {
        DeferredEnter pending = enter;
        enter = null;
        return pending;
    }

    Command deferEnter(TerminalInput.Kind kind, int cursor) {
        enter = new DeferredEnter(++enterSequence, kind, batch != null ? batch.start() : cursor);
        EnterMessage message = new EnterMessage(enter.sequence());
        return Command.tick(StreamedInputBatch.IDLE_INTERVAL, ignored -> message);
    }

    DeferredEnter consumeEnter(EnterMessage message) {
        return enter != null && enter.sequence() == message.sequence() ? takeEnter() : null;
    }

    /** New fragments move the deadline; they do not each create a timer and an eventual UI message. */
    Command scheduleIdle() {
        idleDeadline = clock.getAsLong() + StreamedInputBatch.IDLE_INTERVAL.toNanos();
        return idleScheduled ? Command.none() : nextIdleCheck(StreamedInputBatch.IDLE_INTERVAL);
    }

    IdleResult checkIdle(IdleMessage message) {
        if (!idleScheduled || message.sequence() != idleSequence || batch == null) {
            return new IdleResult(false, Command.none());
        }
        idleScheduled = false;
        if (enter != null) {
            // The independent Enter guard still decides whether this break submits or joins the batch.
            return new IdleResult(false, Command.none());
        }
        long remaining = idleDeadline - clock.getAsLong();
        if (remaining > 0) {
            return new IdleResult(false, nextIdleCheck(Duration.ofNanos(remaining)));
        }
        return new IdleResult(true, Command.none());
    }

    private Command nextIdleCheck(Duration delay) {
        idleScheduled = true;
        IdleMessage message = new IdleMessage(++idleSequence);
        // tui4j's timer has millisecond resolution; round up instead of scheduling a zero-delay loop.
        Duration rounded = Duration.ofMillis(Math.max(1, (delay.toNanos() + 999_999) / 1_000_000));
        return new IdleCheck(message, rounded);
    }

    record DeferredEnter(long sequence, TerminalInput.Kind kind, int start) {}

    record EnterMessage(long sequence) implements Message {}

    record IdleMessage(long sequence) implements Message {}

    record IdleResult(boolean settled, Command command) {}

    record IdleCheck(IdleMessage message, Duration delay) implements Command {
        @Override
        public Message execute() {
            return Command.tick(delay, ignored -> message).execute();
        }
    }
}
