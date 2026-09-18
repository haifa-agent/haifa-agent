package io.haifa.agent.application.coding.terminal.state;

import java.util.Objects;

/** Process-local identity and optional short label for the currently timed terminal activity. */
public record TerminalActivity(long revision, String label) {
    public TerminalActivity {
        if (revision < 0) throw new IllegalArgumentException("activity revision must not be negative");
        label = Objects.requireNonNull(label, "activity label must not be null").strip();
        if (label.length() > 128) throw new IllegalArgumentException("activity label is too long");
    }

    public static TerminalActivity initial() {
        return new TerminalActivity(0, "");
    }

    public TerminalActivity advance(String nextLabel) {
        return new TerminalActivity(Math.addExact(revision, 1), nextLabel);
    }
}
