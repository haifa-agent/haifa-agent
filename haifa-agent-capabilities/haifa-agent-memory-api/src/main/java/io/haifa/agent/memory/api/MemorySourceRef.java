package io.haifa.agent.memory.api;

import io.haifa.agent.core.reference.AssetRef;
import java.util.Objects;
import java.util.Optional;

public record MemorySourceRef(MemorySourceType type, String sourceId, Optional<AssetRef> assetRef) {
    public MemorySourceRef {
        type = Objects.requireNonNull(type, "type must not be null");
        sourceId = MemoryValues.text(sourceId, "sourceId", 512);
        assetRef = Objects.requireNonNull(assetRef, "assetRef must not be null");
        if (type == MemorySourceType.DERIVED_ASSET && assetRef.isEmpty()) {
            throw new IllegalArgumentException("derived asset source requires AssetRef");
        }
        if (type != MemorySourceType.DERIVED_ASSET && assetRef.isPresent()) {
            throw new IllegalArgumentException("only derived asset sources may carry AssetRef");
        }
    }
}
