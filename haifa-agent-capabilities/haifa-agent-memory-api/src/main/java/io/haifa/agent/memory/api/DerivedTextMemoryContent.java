package io.haifa.agent.memory.api;

import io.haifa.agent.core.reference.AssetRef;
import java.util.Objects;

public record DerivedTextMemoryContent(AssetRef derivedAsset, DerivedTextType type, String text)
        implements MemoryContent {
    public DerivedTextMemoryContent {
        derivedAsset = Objects.requireNonNull(derivedAsset, "derivedAsset must not be null");
        type = Objects.requireNonNull(type, "type must not be null");
        text = MemoryValues.content(text, "text", 4096);
    }

    @Override
    public String boundedText() {
        return text;
    }

    @Override
    public int estimatedTokens() {
        return Math.max(1, (text.length() + 3) / 4);
    }
}
