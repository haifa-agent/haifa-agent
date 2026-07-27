package io.haifa.agent.application.coding.terminal.jline;

import java.util.Objects;

public record TerminalInput(Kind kind, String text, int cursor) {
    public enum Kind {
        TICK,
        EDITOR_CHANGED,
        COMPLETION_REQUESTED,
        SUBMIT,
        FOLLOW_UP,
        CANCEL_OR_CLOSE,
        RESTORE,
        TOGGLE_EXPANSION,
        SELECT_PREVIOUS,
        SELECT_NEXT,
        INTERRUPT,
        EOF
    }

    public TerminalInput {
        kind = Objects.requireNonNull(kind, "kind must not be null");
        text = Objects.requireNonNull(text, "text must not be null");
        if (cursor < 0 || cursor > text.length()) {
            throw new IllegalArgumentException("cursor is out of range");
        }
    }

    public TerminalInput(Kind kind, String text) {
        this(kind, text, text.length());
    }
}
