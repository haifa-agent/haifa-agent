package io.haifa.agent.mcp.protocol;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record McpRemoteToolResult(
        boolean error, List<McpRemoteContent> content, Map<String, Object> structuredContent) {
    public McpRemoteToolResult {
        content = List.copyOf(Objects.requireNonNull(content, "content"));
        structuredContent = Map.copyOf(Objects.requireNonNull(structuredContent, "structuredContent"));
    }
}
