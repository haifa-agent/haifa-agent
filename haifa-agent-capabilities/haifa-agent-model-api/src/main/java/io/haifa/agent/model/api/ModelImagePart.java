package io.haifa.agent.model.api;

/** Provider-neutral image input attached to one user chat message. */
public sealed interface ModelImagePart permits ImageUrlPart, ImageDataPart {}
