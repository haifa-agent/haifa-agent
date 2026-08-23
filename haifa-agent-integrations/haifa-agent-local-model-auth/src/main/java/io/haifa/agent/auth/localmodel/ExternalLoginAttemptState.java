package io.haifa.agent.auth.localmodel;

public enum ExternalLoginAttemptState {
    CREATED,
    AUTHORIZING,
    WAITING_USER,
    EXCHANGING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    EXPIRED
}
