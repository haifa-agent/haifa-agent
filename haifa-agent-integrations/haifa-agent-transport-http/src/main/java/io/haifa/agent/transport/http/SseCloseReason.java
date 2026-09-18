package io.haifa.agent.transport.http;

public enum SseCloseReason {
    OPEN,
    CLIENT_DISCONNECTED,
    NORMAL,
    AUTHORIZATION_REVOKED,
    SLOW_CONSUMER,
    SERIALIZATION_FAILED,
    RUNTIME_CLOSED
}
