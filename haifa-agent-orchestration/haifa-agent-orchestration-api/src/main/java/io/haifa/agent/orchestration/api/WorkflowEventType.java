package io.haifa.agent.orchestration.api;

public enum WorkflowEventType {
    RUN_STARTED,
    NODE_STARTED,
    NODE_COMPLETED,
    WAITING,
    RESUMED,
    COMPLETED,
    FAILED,
    CANCELLED
}
