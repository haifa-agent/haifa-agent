package io.haifa.agent.application.coding.terminal.event;

import java.util.Objects;

public record TerminalInput(Kind kind, String text, int cursor) {
    public enum Kind {
        TICK,
        EDITOR_CHANGED,
        COMPLETION_REQUESTED,
        SUBMIT,
        FOLLOW_UP,
        EDIT_WAITING,
        CANCEL_OR_CLOSE,
        RESTORE,
        TOGGLE_EXPANSION,
        TOGGLE_TECHNICAL_DETAILS,
        SELECT_PREVIOUS,
        SELECT_NEXT,
        TOGGLE_SELECTION,
        NAVIGATE_BACK,
        SELECTOR_FILTER,
        SELECTOR_BACKSPACE,
        INTERRUPT,
        EOF,
        COMMAND_PALETTE,
        CYCLE_MODEL_NEXT,
        CYCLE_MODEL_PREVIOUS,
        EXECUTE_COMMAND
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
