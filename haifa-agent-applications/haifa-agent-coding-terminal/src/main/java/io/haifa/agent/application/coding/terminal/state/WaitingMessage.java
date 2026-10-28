package io.haifa.agent.application.coding.terminal.state;

import java.util.Objects;

/** Local draft above the editor while a Run is active; not yet submitted as Steer. */
public record WaitingMessage(String id, String text, Phase phase, String originalText) {
    public WaitingMessage {
        id = require(id, "id", 256);
        text = Objects.requireNonNull(text, "text must not be null");
        if (text.isBlank() || text.length() > 8_192) {
            throw new IllegalArgumentException("text is invalid");
        }
        phase = Objects.requireNonNull(phase, "phase must not be null");
        originalText = Objects.requireNonNull(originalText, "originalText must not be null");
        if (originalText.isBlank() || originalText.length() > 8_192) {
            throw new IllegalArgumentException("originalText is invalid");
        }
    }

    public WaitingMessage(String id, String text, Phase phase) {
        this(id, text, phase, text);
    }

    public WaitingMessage withPhase(Phase newPhase) {
        return new WaitingMessage(id, text, newPhase, originalText);
    }

    public WaitingMessage withText(String newText) {
        return new WaitingMessage(id, newText, phase, originalText);
    }

    public WaitingMessage beginEdit() {
        return new WaitingMessage(id, text, Phase.EDITING, text);
    }

    public WaitingMessage discardEdit() {
        return new WaitingMessage(id, originalText, Phase.WAITING, originalText);
    }

    public WaitingMessage saveEdit(String newText) {
        String normalized =
                Objects.requireNonNull(newText, "newText must not be null").strip();
        if (normalized.isEmpty()) {
            return discardEdit();
        }
        return new WaitingMessage(id, normalized, Phase.WAITING, normalized);
    }

    public enum Phase {
        WAITING,
        EDITING,
        SENDING
    }

    private static String require(String value, String field, int maximum) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty() || normalized.length() > maximum) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }
}
