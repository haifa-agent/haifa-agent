package io.haifa.agent.sdk.api;

import io.haifa.agent.core.content.StoredImageContentPart;
import io.haifa.agent.model.api.ImageDataPart;

/** Resolves a product-owned opaque image reference only while assembling a model request. */
@FunctionalInterface
public interface ModelImageResolver {
    ImageDataPart resolve(StoredImageContentPart image);

    static ModelImageResolver unsupported() {
        return image -> {
            throw new IllegalStateException("no model image resolver is configured for store: " + image.storeId());
        };
    }
}
