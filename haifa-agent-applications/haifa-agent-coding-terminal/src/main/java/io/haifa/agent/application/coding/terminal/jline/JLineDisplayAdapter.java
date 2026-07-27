package io.haifa.agent.application.coding.terminal.jline;

import io.haifa.agent.application.coding.terminal.view.TerminalView;
import java.util.Objects;
import org.jline.terminal.Terminal;
import org.jline.utils.Display;

/** JLine differential display owned by exactly one UI thread. */
public final class JLineDisplayAdapter {
    private final Terminal terminal;
    private final Display display;
    private Thread owner;

    public JLineDisplayAdapter(Terminal terminal) {
        this.terminal = Objects.requireNonNull(terminal, "terminal must not be null");
        this.display = new Display(terminal, false);
    }

    public void render(TerminalView view) {
        claimOwner();
        display.resize(terminal.getHeight(), terminal.getWidth());
        int cursor = 0;
        for (int row = 0; row < Math.min(view.cursorRow(), view.lines().size()); row++) {
            cursor += view.lines().get(row).columnLength() + 1;
        }
        cursor += Math.max(0, view.cursorColumn());
        display.update(view.lines(), cursor);
        terminal.flush();
    }

    public void reset() {
        claimOwner();
        display.reset();
    }

    private void claimOwner() {
        Thread current = Thread.currentThread();
        if (owner == null) {
            owner = current;
        } else if (owner != current) {
            throw new IllegalStateException("TERMINAL_UI_THREAD_VIOLATION");
        }
    }
}
