package io.haifa.agent.application.coding.terminal.tui4j;

import com.williamcallahan.tui4j.compat.bubbletea.Message;
import sun.misc.Signal;
import sun.misc.SignalHandler;

/** Keeps console Ctrl+C available to a thin client without claiming its termination signal. */
final class TerminalInterruptSignal implements AutoCloseable {
    record Interrupted() implements Message {}

    private final Signal interrupt = new Signal("INT");
    private final SignalHandler handler;
    private final SignalHandler previous;

    TerminalInterruptSignal(Runnable onInterrupt) {
        handler = ignored -> onInterrupt.run();
        previous = Signal.handle(interrupt, handler);
    }

    @Override
    public void close() {
        SignalHandler current = Signal.handle(interrupt, previous);
        if (current != handler) {
            // Terminal cleanup may already have restored its original handler. Do not resurrect
            // the terminal's now-closed handler over that newer restoration.
            Signal.handle(interrupt, current);
        }
    }
}
