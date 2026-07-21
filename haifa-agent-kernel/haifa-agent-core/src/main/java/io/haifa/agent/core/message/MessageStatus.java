package io.haifa.agent.core.message;

/** Persistence lifecycle of an independently stored message. */
public enum MessageStatus {
    CREATED,
    STREAMING,
    COMPLETED,
    FAILED,
    REDACTED,
    DELETED
}
