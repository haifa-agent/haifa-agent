package io.haifa.agent.orchestration.api;

public enum WorkflowNodeType {
    ACTION,
    AGENT_RUN,
    SUBGRAPH,
    FORK_ALL,
    JOIN_ALL,
    WAIT,
    TERMINAL
}
