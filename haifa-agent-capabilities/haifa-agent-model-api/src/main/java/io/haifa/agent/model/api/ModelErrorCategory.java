package io.haifa.agent.model.api;

/** Normalized model invocation failures. */
public enum ModelErrorCategory {
    AUTHENTICATION_FAILED,
    PERMISSION_DENIED,
    RATE_LIMITED,
    TIMEOUT,
    PROVIDER_UNAVAILABLE,
    INVALID_REQUEST,
    MODEL_NOT_FOUND,
    CONTEXT_TOO_LONG,
    CONTENT_REJECTED,
    MALFORMED_RESPONSE,
    CANCELLED,
    UNKNOWN_PROVIDER_ERROR
}
