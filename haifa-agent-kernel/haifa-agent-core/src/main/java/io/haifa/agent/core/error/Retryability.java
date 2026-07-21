package io.haifa.agent.core.error;

/** Whether a new attempt may recover from an error. */
public enum Retryability {
    RETRYABLE,
    RETRYABLE_WITH_BACKOFF,
    RETRYABLE_AFTER_INTERACTION,
    NOT_RETRYABLE,
    UNKNOWN
}
