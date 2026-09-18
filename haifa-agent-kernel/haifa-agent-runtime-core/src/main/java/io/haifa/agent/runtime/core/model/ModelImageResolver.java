package io.haifa.agent.runtime.core.model;

import io.haifa.agent.core.content.StoredImageContentPart;
import io.haifa.agent.model.api.ImageDataPart;

/** Resolves an opaque persisted image reference only while assembling a model request. */
@FunctionalInterface
public interface ModelImageResolver {
    ImageDataPart resolve(StoredImageContentPart image);

    static ModelImageResolver unsupported() {
        return image -> {
            throw new IllegalStateException("no model image resolver is configured for store: " + image.storeId());
        };
    }
}
