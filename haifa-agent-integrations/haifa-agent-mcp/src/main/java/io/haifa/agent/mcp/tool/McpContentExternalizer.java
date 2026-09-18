package io.haifa.agent.mcp.tool;

import io.haifa.agent.core.reference.AssetRef;
import io.haifa.agent.mcp.protocol.McpRemoteContent;

@FunctionalInterface
public interface McpContentExternalizer {
    AssetRef externalize(McpRemoteContent content);
}
