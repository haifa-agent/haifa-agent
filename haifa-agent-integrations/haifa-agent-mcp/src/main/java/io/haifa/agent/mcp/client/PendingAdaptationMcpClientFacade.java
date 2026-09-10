package io.haifa.agent.mcp.client;

import io.haifa.agent.mcp.config.McpProtocolProfile;
import io.haifa.agent.mcp.config.McpServerDefinition;
import io.haifa.agent.mcp.protocol.McpListToolsPage;
import io.haifa.agent.mcp.protocol.McpRemoteToolResult;
import io.haifa.agent.tool.api.ToolDispatchState;
import io.haifa.agent.tool.api.ToolInvocationException;
import io.haifa.agent.tool.api.ToolInvocationObserver;
import java.util.Map;

final class PendingAdaptationMcpClientFacade implements McpClientFacade {
    private final McpServerDefinition server;
    private final McpTelemetry telemetry;

    PendingAdaptationMcpClientFacade(McpServerDefinition server, McpTelemetry telemetry) {
        this.server = server;
        this.telemetry = telemetry;
        telemetry.stateChanged(server.serverId(), McpConnectionState.DISCONNECTED);
    }

    @Override
    public McpServerSnapshot initialize(Map<String, String> credentials) {
        throw pendingAdaptation();
    }

    @Override
    public McpListToolsPage listTools(String cursor, Map<String, String> credentials) {
        throw pendingAdaptation();
    }

    @Override
    public McpRemoteToolResult callTool(
            String name,
            Map<String, Object> arguments,
            Map<String, String> credentials,
            ToolInvocationObserver observer) {
        throw pendingAdaptation();
    }

    @Override
    public McpConnectionState state() {
        return McpConnectionState.DISCONNECTED;
    }

    @Override
    public void close() {}

    private ToolInvocationException pendingAdaptation() {
        telemetry.operationFailed(server.serverId(), "MCP_PROTOCOL_VERSION_PENDING_ADAPTATION");
        return new ToolInvocationException(
                "MCP_PROTOCOL_VERSION_PENDING_ADAPTATION",
                ToolDispatchState.NOT_DISPATCHED,
                McpProtocolProfile.adaptationNotice(server.protocol().targetVersion()));
    }
}
