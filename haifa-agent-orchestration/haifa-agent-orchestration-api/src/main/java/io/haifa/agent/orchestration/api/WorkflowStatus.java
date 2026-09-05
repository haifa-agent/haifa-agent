package io.haifa.agent.orchestration.api;

public enum WorkflowStatus {
    RUNNING,
    WAITING,
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMED_OUT;

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED || this == TIMED_OUT;
    }
}
