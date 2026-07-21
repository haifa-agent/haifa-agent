package io.haifa.agent.execution.api;

import io.haifa.agent.core.reference.AssetRef;
import java.util.Objects;
import java.util.Optional;

public record ExecutionOutput(
        String summary, AssetRef assetRef, long byteCount, String sha256, boolean truncated, boolean binary) {
    public ExecutionOutput {
        summary = Objects.requireNonNull(summary, "summary must not be null");
        if (byteCount < 0) throw new IllegalArgumentException("byteCount must not be negative");
        sha256 = Objects.requireNonNull(sha256, "sha256 must not be null");
    }

    public Optional<AssetRef> optionalAssetRef() {
        return Optional.ofNullable(assetRef);
    }

    @Override
    public String toString() {
        return "ExecutionOutput[bytes=" + byteCount + ", sha256=" + sha256 + ", truncated=" + truncated + ", binary="
                + binary + ", asset=" + (assetRef != null) + "]";
    }
}
