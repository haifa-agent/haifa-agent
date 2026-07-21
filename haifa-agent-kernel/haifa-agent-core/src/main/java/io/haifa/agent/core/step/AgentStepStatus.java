package io.haifa.agent.core.step;

/** Observable step lifecycle. */
public enum AgentStepStatus {
    PENDING,
    RUNNING,
    WAITING,
    COMPLETED,
    FAILED,
    CANCELLED,
    SKIPPED
}
