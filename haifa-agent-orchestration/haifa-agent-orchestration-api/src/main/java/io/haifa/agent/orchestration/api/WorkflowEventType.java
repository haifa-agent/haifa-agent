package io.haifa.agent.orchestration.api;

public enum WorkflowEventType {
    RUN_STARTED,
    NODE_STARTED,
    NODE_COMPLETED,
    SUBGRAPH_STARTED,
    SUBGRAPH_COMPLETED,
    WAITING,
    RESUMED,
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMED_OUT
}
