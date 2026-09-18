package io.haifa.agent.store.sqlite.payload;

import io.haifa.agent.core.content.StoredAudioContentPart;

public record StoredAudioPayload(
        String storeId, String audioId, String mediaType, long sizeBytes, String sha256, String originalFilename) {
    static StoredAudioPayload from(StoredAudioContentPart value) {
        return new StoredAudioPayload(
                value.storeId(),
                value.audioId(),
                value.mediaType(),
                value.sizeBytes(),
                value.sha256(),
                value.originalFilename());
    }

    StoredAudioContentPart toDomain() {
        return new StoredAudioContentPart(storeId, audioId, mediaType, sizeBytes, sha256, originalFilename);
    }
}
