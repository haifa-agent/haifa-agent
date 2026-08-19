package io.haifa.agent.orchestration.api;

public enum WorkflowCapability {
    SEQUENCE,
    CONDITION,
    BOUNDED_LOOP,
    FIXED_ALL_OF,
    INTERRUPTION,
    SUBGRAPH,
    DYNAMIC_FAN_OUT,
    ANY_OF
}
