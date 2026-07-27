package io.haifa.agent.application.coding.terminal.jline;

import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.jline.terminal.Attributes;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

/** Owns terminal modes and guarantees restoration without entering an alternate screen. */
public final class JLineTerminalLifecycle implements AutoCloseable {
    public static final String TUI_UNAVAILABLE = "TUI_UNAVAILABLE";

    private final Terminal terminal;
    private final Attributes originalAttributes;
    private final Map<Terminal.Signal, Terminal.SignalHandler> originalSignalHandlers =
            new EnumMap<>(Terminal.Signal.class);
    private final AtomicBoolean closed = new AtomicBoolean();

    private JLineTerminalLifecycle(Terminal terminal) {
        this.terminal = Objects.requireNonNull(terminal, "terminal must not be null");
        this.originalAttributes = new Attributes(terminal.getAttributes());
    }

    public static JLineTerminalLifecycle openSystem() {
        try {
            Terminal terminal = TerminalBuilder.builder()
                    .system(true)
                    .provider("jni")
                    .jni(true)
                    .exec(false)
                    .dumb(false)
                    .nativeSignals(true)
                    .build();
            if ("dumb".equalsIgnoreCase(terminal.getType())) {
                terminal.close();
                throw new IllegalStateException(TUI_UNAVAILABLE);
            }
            return new JLineTerminalLifecycle(terminal);
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof IllegalStateException state && TUI_UNAVAILABLE.equals(state.getMessage())) {
                throw state;
            }
            throw new IllegalStateException(TUI_UNAVAILABLE, exception);
        }
    }

    public static JLineTerminalLifecycle forTerminal(Terminal terminal) {
        return new JLineTerminalLifecycle(terminal);
    }

    public Terminal terminal() {
        return terminal;
    }

    public void enterRawMode() {
        terminal.enterRawMode();
    }

    /**
     * Installs callbacks that must only enqueue UI actions. The previous handlers are restored on
     * close.
     */
    public void installSignalHandlers(Consumer<Size> resize, Runnable interrupt) {
        Objects.requireNonNull(resize, "resize must not be null");
        Objects.requireNonNull(interrupt, "interrupt must not be null");
        originalSignalHandlers.computeIfAbsent(
                Terminal.Signal.WINCH,
                signal -> terminal.handle(signal, ignored -> {
                    Size current = terminal.getSize();
                    resize.accept(new Size(current.getColumns(), current.getRows()));
                }));
        originalSignalHandlers.computeIfAbsent(
                Terminal.Signal.INT, signal -> terminal.handle(signal, ignored -> interrupt.run()));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        originalSignalHandlers.forEach(terminal::handle);
        terminal.setAttributes(originalAttributes);
        terminal.flush();
        try {
            terminal.close();
        } catch (IOException exception) {
            throw new IllegalStateException("TUI_RESTORE_FAILED", exception);
        }
    }
}
