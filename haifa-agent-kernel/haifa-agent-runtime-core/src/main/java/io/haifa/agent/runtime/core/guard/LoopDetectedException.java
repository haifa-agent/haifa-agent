package io.haifa.agent.runtime.core.guard;

import java.util.Objects;

/** Safe typed signal for a decision loop that must terminate the current Run. */
public final class LoopDetectedException extends IllegalStateException {
    private final Reason reason;

    public LoopDetectedException(Reason reason) {
        super(message(Objects.requireNonNull(reason, "reason must not be null")));
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    private static String message(Reason reason) {
        return switch (reason) {
            case REPEATED_DECISION -> "repeated decision loop detected";
            case ALTERNATING_DECISION -> "alternating decision loop detected";
            case NO_OBSERVABLE_PROGRESS -> "loop made no observable progress";
        };
    }

    public enum Reason {
        REPEATED_DECISION,
        ALTERNATING_DECISION,
        NO_OBSERVABLE_PROGRESS
    }
}
