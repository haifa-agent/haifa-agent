package io.haifa.agent.runtime.core.attempt;

public enum ExecutionAttemptStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    PAUSED,
    CANCELLED,
    FAILED,
    ABANDONED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == PAUSED || this == CANCELLED || this == FAILED || this == ABANDONED;
    }
}
