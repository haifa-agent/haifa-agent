package io.haifa.agent.core.reference;

import static io.haifa.agent.core.support.DomainValues.requireText;

/** Lightweight reference to externally managed source content. */
public record AssetRef(String assetId, String mimeType, String filename) {
    public AssetRef {
        assetId = requireText(assetId, "assetId");
        mimeType = requireText(mimeType, "mimeType");
        filename = requireText(filename, "filename");
    }
}
