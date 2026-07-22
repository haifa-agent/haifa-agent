package io.haifa.agent.mcp.client;

import io.haifa.agent.mcp.config.McpServerId;

public interface McpTelemetry {
    void stateChanged(McpServerId serverId, McpConnectionState state);

    void operationFailed(McpServerId serverId, String errorCode);

    static McpTelemetry noop() {
        return new McpTelemetry() {
            @Override
            public void stateChanged(McpServerId serverId, McpConnectionState state) {}

            @Override
            public void operationFailed(McpServerId serverId, String errorCode) {}
        };
    }
}
