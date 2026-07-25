package io.haifa.agent.store.sqlite.payload;

import io.haifa.agent.core.reference.AssetRef;

public record AssetPayload(String assetId, String mimeType, String filename) {
    public static AssetPayload from(AssetRef reference) {
        return new AssetPayload(reference.assetId(), reference.mimeType(), reference.filename());
    }

    public AssetRef toDomain() {
        return new AssetRef(assetId, mimeType, filename);
    }
}
