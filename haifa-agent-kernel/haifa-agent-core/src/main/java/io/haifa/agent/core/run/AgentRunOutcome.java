package io.haifa.agent.core.run;

/** Business outcome of a successfully finalized run. */
public enum AgentRunOutcome {
    SUCCESS,
    PARTIAL_SUCCESS,
    NO_ACTION_REQUIRED,
    USER_CANCELLED,
    POLICY_BLOCKED,
    INSUFFICIENT_INFORMATION
}
