package io.haifa.agent.model.api;

/** Provider-neutral capability verified by the active adapter. */
public enum ModelCapability {
    TEXT_CHAT,
    IMAGE_INPUT,
    AUDIO_INPUT,
    TOOL_CALLING,
    STRUCTURED_OUTPUT,
    REASONING
}
