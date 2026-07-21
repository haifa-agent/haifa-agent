package io.haifa.agent.core.run;

/** How a run entered the execution graph. */
public enum AgentInvocationMode {
    ROOT,
    AGENT_AS_TOOL,
    HANDOFF,
    FORK_JOIN,
    SUBGRAPH,
    SCHEDULED,
    EVENT_TRIGGERED
}
