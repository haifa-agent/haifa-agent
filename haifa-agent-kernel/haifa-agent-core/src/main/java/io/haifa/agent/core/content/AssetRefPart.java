package io.haifa.agent.core.content;

import io.haifa.agent.core.reference.AssetRef;
import java.util.Objects;

/** Message content that points to a source asset instead of embedding binary data. */
public record AssetRefPart(AssetRef asset) implements ContentPart {
    public AssetRefPart {
        asset = Objects.requireNonNull(asset, "asset must not be null");
    }

    @Override
    public String contentType() {
        return "asset-ref";
    }
}
