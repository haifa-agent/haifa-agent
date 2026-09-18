package io.haifa.agent.runtime.core.model;

import io.haifa.agent.core.content.StoredAudioContentPart;
import io.haifa.agent.model.api.AudioDataPart;

@FunctionalInterface
public interface ModelAudioResolver {
    AudioDataPart resolve(StoredAudioContentPart audio);

    static ModelAudioResolver unsupported() {
        return audio -> {
            throw new IllegalStateException("no model audio resolver is configured for store: " + audio.storeId());
        };
    }
}
