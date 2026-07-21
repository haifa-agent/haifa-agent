package io.haifa.agent.core.run;

import java.util.EnumSet;
import java.util.Set;

/** Lifecycle states controlled by the Haifa Agent Runtime. */
public enum AgentRunStatus {
    PENDING,
    QUEUED,
    RUNNING,
    SUSPENDING,
    SUSPENDED,
    WAITING_INTERACTION,
    WAITING_APPROVAL,
    COMPLETING,
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMEOUT;

    private static final Set<AgentRunStatus> TERMINAL = EnumSet.of(COMPLETED, FAILED, CANCELLED, TIMEOUT);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }
}
