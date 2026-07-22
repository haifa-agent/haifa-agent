package io.haifa.agent.tool.api;

public enum ToolIdempotency {
    PURE,
    IDEMPOTENT,
    IDEMPOTENT_WITH_KEY,
    NON_IDEMPOTENT,
    UNKNOWN
}
