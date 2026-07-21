package io.haifa.agent.core.plan;

/** Lifecycle of one plan item. */
public enum TodoStatus {
    PENDING,
    IN_PROGRESS,
    BLOCKED,
    COMPLETED,
    CANCELLED,
    SKIPPED
}
