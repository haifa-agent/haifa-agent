package io.haifa.agent.mcp.protocol;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record McpListToolsPage(List<McpRemoteTool> tools, Optional<String> nextCursor) {
    public McpListToolsPage {
        tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
        nextCursor = Objects.requireNonNull(nextCursor, "nextCursor");
    }
}
