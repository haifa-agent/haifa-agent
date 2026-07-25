package io.haifa.agent.store.sqlite.payload;

import io.haifa.agent.core.tool.ToolResult;
import java.util.List;
import java.util.Map;

public record ToolResultPayload(
        boolean successful,
        String summary,
        Map<String, Object> structuredData,
        List<AssetPayload> assets,
        List<ArtifactPayload> artifacts,
        boolean truncated) {
    public static ToolResultPayload from(ToolResult value) {
        return new ToolResultPayload(
                value.successful(),
                value.summary(),
                value.structuredData(),
                value.assets().stream().map(AssetPayload::from).toList(),
                value.artifacts().stream().map(ArtifactPayload::from).toList(),
                value.truncated());
    }

    public ToolResult toDomain() {
        return new ToolResult(
                successful,
                summary,
                structuredData,
                assets.stream().map(AssetPayload::toDomain).toList(),
                artifacts.stream().map(ArtifactPayload::toDomain).toList(),
                truncated);
    }
}
