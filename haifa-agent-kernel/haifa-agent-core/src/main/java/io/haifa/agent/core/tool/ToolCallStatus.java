package io.haifa.agent.core.tool;

/** Detailed lifecycle of one actual tool invocation. */
public enum ToolCallStatus {
    REQUESTED,
    VALIDATING,
    POLICY_CHECK,
    WAITING_APPROVAL,
    APPROVED,
    RUNNING,
    COMPLETED,
    FAILED,
    DENIED,
    CANCELLED,
    TIMEOUT
}
