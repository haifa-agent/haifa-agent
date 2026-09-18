package io.haifa.agent.mcp.client;

import java.util.Objects;

public record McpConnection(McpClientFacade client, McpServerSnapshot serverSnapshot) {
    public McpConnection {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(serverSnapshot, "serverSnapshot");
        if (client.state() != McpConnectionState.READY) {
            throw new IllegalArgumentException("MCP connection must be ready");
        }
    }
}
