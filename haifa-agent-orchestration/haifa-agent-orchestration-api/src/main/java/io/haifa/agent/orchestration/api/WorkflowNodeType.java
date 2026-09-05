package io.haifa.agent.orchestration.api;

public enum WorkflowNodeType {
    ACTION,
    AGENT_RUN,
    SUBGRAPH,
    FORK_ALL,
    JOIN_ALL,
    FORK_DYNAMIC,
    FORK_ANY,
    JOIN_ANY,
    WAIT,
    TERMINAL
}
