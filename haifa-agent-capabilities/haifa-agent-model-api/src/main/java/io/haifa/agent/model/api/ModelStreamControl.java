package io.haifa.agent.model.api;

/** Backpressure/cancellation signal returned synchronously to a streaming model adapter. */
public enum ModelStreamControl {
    CONTINUE,
    CANCEL
}
