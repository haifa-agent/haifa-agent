package io.haifa.agent.core.error;

/** Stable error classification for Runtime policy and reporting. */
public enum AgentErrorCategory {
    VALIDATION,
    RESOURCE_LIMIT,
    CONFIGURATION,
    MODEL,
    TOOL,
    POLICY,
    SANDBOX,
    KNOWLEDGE,
    STORAGE,
    INTERACTION,
    TIMEOUT,
    CANCELLED,
    INTERNAL
}
