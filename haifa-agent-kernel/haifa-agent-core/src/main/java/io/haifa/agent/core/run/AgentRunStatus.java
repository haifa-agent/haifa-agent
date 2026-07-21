package io.haifa.agent.core.run;

import java.util.EnumSet;
import java.util.Set;

/** Lifecycle states controlled by the Haifa Agent Runtime. */
public enum AgentRunStatus {
    NEW,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED;

    private static final Set<AgentRunStatus> TERMINAL = EnumSet.of(COMPLETED, FAILED, CANCELLED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public boolean canTransitionTo(AgentRunStatus target) {
        return switch (this) {
            case NEW -> target == RUNNING || target == CANCELLED;
            case RUNNING -> target == PAUSED || target == COMPLETED || target == FAILED || target == CANCELLED;
            case PAUSED -> target == RUNNING || target == CANCELLED;
            case COMPLETED, FAILED, CANCELLED -> false;
        };
    }
}
