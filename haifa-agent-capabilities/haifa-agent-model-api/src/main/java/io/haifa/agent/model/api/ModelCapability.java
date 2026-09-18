package io.haifa.agent.model.api;

/** Provider-neutral capability verified by the active adapter. */
public enum ModelCapability {
    TEXT_CHAT,
    IMAGE_UPLOAD_INPUT,
    IMAGE_URL_INPUT,
    /** Retained only for decoding frozen snapshots created before image capabilities were split. */
    @Deprecated
    IMAGE_INPUT,
    AUDIO_INPUT,
    TOOL_CALLING,
    STRUCTURED_OUTPUT,
    REASONING
}
