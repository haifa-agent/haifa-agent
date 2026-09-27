package io.haifa.agent.runtime.api;

import io.haifa.agent.core.reference.ArtifactRef;
import io.haifa.agent.core.reference.AssetRef;
import io.haifa.agent.runtime.api.display.BoundedText;
import java.util.List;
import java.util.Objects;

/** Read-only display projection of a persisted Tool Result. */
public record ToolResultView(
        boolean successful,
        BoundedText summary,
        ToolDataView structuredData,
        List<AssetRef> assets,
        List<ArtifactRef> artifacts,
        boolean truncated) {
    public ToolResultView {
        summary = Objects.requireNonNull(summary, "summary must not be null");
        structuredData = Objects.requireNonNull(structuredData, "structuredData must not be null");
        assets = List.copyOf(Objects.requireNonNull(assets, "assets must not be null"));
        artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts must not be null"));
    }
}
