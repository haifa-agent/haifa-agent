package io.haifa.agent.core.message;

/** Audience boundary independent of model-context selection policy. */
public enum MessageVisibility {
    USER_VISIBLE,
    AGENT_VISIBLE,
    ADMIN_VISIBLE,
    INTERNAL,
    HIDDEN,
    REDACTED
}
