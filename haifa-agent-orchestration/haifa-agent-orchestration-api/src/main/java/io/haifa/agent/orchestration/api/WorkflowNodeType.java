package io.haifa.agent.orchestration.api;

public enum WorkflowNodeType {
    ACTION,
    AGENT_RUN,
    FORK_ALL,
    JOIN_ALL,
    WAIT,
    TERMINAL
}
