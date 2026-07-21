package io.haifa.agent.core.error;

/** Operational severity without leaking a logging framework type. */
public enum AgentErrorSeverity {
    INFO,
    WARNING,
    ERROR,
    CRITICAL
}
