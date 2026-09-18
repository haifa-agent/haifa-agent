package io.haifa.agent.runtime.api;

/** Public lifecycle of one blocking interaction. */
public enum InteractionState {
    PENDING,
    RESPONDED,
    EXPIRED,
    CANCELLED,
    INVALIDATED,
    APPLIED
}
