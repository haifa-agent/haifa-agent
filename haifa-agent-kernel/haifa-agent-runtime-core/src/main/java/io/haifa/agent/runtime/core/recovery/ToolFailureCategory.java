package io.haifa.agent.runtime.core.recovery;

public enum ToolFailureCategory {
    ENVIRONMENT_UNAVAILABLE,
    FILESYSTEM_DENIED,
    NETWORK_DENIED,
    DEPENDENCY_UNAVAILABLE,
    INVALID_INPUT,
    COMMAND_FAILED,
    TIMEOUT,
    CANCELLED,
    OUTCOME_UNKNOWN,
    POLICY_DENIED,
    UNKNOWN
}
