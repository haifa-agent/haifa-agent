package io.haifa.agent.context.item;

import io.haifa.agent.core.reference.AssetRef;
import java.util.Objects;

/** Bounded text derived from an Asset; raw multimodal bytes are intentionally not representable. */
public record AssetDerivedTextContent(AssetRef asset, DerivedTextKind kind, String text) implements ContextContent {
    public AssetDerivedTextContent {
        asset = Objects.requireNonNull(asset, "asset must not be null");
        kind = Objects.requireNonNull(kind, "kind must not be null");
        text = Objects.requireNonNull(text, "text must not be null").trim();
        if (text.isEmpty()) throw new IllegalArgumentException("derived text must not be blank");
    }
}
