package io.haifa.agent.sandbox.api;

public enum SandboxProcessStatus {
    EXITED,
    OUTPUT_LIMIT_EXCEEDED,
    PROCESS_LIMIT_EXCEEDED,
    TIMED_OUT,
    CANCELLED,
    UNKNOWN
}
