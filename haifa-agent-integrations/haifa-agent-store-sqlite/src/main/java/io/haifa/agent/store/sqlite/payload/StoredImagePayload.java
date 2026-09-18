package io.haifa.agent.store.sqlite.payload;

import io.haifa.agent.core.content.StoredImageContentPart;

public record StoredImagePayload(
        String storeId, String imageId, String mediaType, long sizeBytes, String sha256, String originalFilename) {
    static StoredImagePayload from(StoredImageContentPart value) {
        return new StoredImagePayload(
                value.storeId(),
                value.imageId(),
                value.mediaType(),
                value.sizeBytes(),
                value.sha256(),
                value.originalFilename());
    }

    StoredImageContentPart toDomain() {
        return new StoredImageContentPart(storeId, imageId, mediaType, sizeBytes, sha256, originalFilename);
    }
}
