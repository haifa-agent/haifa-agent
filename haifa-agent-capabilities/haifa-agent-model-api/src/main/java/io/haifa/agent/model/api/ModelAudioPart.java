package io.haifa.agent.model.api;

/** Provider-neutral audio input resolved only for the current model request. */
public sealed interface ModelAudioPart permits AudioDataPart {}
