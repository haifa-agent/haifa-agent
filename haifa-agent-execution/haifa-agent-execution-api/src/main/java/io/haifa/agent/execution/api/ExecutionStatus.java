package io.haifa.agent.execution.api;

public enum ExecutionStatus {
    SUCCEEDED,
    FAILED,
    OUTPUT_LIMIT_EXCEEDED,
    PROCESS_LIMIT_EXCEEDED,
    TIMED_OUT,
    CANCELLED,
    UNKNOWN
}
