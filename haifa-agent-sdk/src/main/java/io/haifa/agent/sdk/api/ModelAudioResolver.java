package io.haifa.agent.sdk.api;

import io.haifa.agent.core.content.StoredAudioContentPart;
import io.haifa.agent.model.api.AudioDataPart;

/** Resolves a product-owned opaque audio reference only while assembling a model request. */
@FunctionalInterface
public interface ModelAudioResolver {
    AudioDataPart resolve(StoredAudioContentPart audio);

    static ModelAudioResolver unsupported() {
        return audio -> {
            throw new IllegalStateException("no model audio resolver is configured for store: " + audio.storeId());
        };
    }
}
