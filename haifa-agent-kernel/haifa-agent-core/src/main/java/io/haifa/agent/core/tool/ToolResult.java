package io.haifa.agent.core.tool;

import static io.haifa.agent.core.support.DomainValues.immutableMap;
import static io.haifa.agent.core.support.DomainValues.requireText;

import io.haifa.agent.core.reference.ArtifactRef;
import io.haifa.agent.core.reference.AssetRef;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Structured result of one actual tool invocation. */
public record ToolResult(
        boolean successful,
        String summary,
        Map<String, Object> structuredData,
        List<AssetRef> assets,
        List<ArtifactRef> artifacts,
        boolean truncated) {
    public ToolResult {
        summary = requireText(summary, "summary");
        structuredData = immutableMap(structuredData, "structuredData");
        assets = List.copyOf(Objects.requireNonNull(assets, "assets must not be null"));
        artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts must not be null"));
    }
}
