package io.haifa.agent.core.message;

/** Provider-neutral identity of a message producer. */
public enum MessageRole {
    SYSTEM,
    DEVELOPER,
    USER,
    ASSISTANT,
    TOOL,
    AGENT,
    RUNTIME
}
