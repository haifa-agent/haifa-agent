package io.haifa.agent.model.api;

/** Normalized reason why a provider stopped generation. */
public enum ModelFinishReason {
    STOP,
    LENGTH,
    CONTENT_FILTER,
    TOOL_CALLS,
    INSUFFICIENT_SYSTEM_RESOURCE,
    UNKNOWN
}
