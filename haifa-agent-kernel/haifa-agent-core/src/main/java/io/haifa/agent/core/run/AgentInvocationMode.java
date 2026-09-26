package io.haifa.agent.core.run;

/** How a run entered the execution graph. */
public enum AgentInvocationMode {
    /** A run started directly by a caller. */
    ROOT,
    /** A child run created by one delegation Tool Call of its parent run. */
    AGENT_AS_TOOL
}
