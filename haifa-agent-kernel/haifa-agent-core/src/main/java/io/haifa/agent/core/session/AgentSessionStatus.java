package io.haifa.agent.core.session;

/** Session lifecycle, independent from the lifecycle of any run. */
public enum AgentSessionStatus {
    ACTIVE,
    ARCHIVED,
    CLOSED,
    DELETED
}
