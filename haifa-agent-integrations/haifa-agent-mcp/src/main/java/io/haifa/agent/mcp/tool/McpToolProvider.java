package io.haifa.agent.mcp.tool;

import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.mcp.client.McpConnection;
import io.haifa.agent.mcp.client.McpConnectionManager;
import io.haifa.agent.mcp.config.McpProtocolProfile;
import io.haifa.agent.mcp.config.McpServerId;
import io.haifa.agent.mcp.config.StdioDefinition;
import io.haifa.agent.tool.api.ToolDispatchState;
import io.haifa.agent.tool.api.ToolInvocationException;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import io.haifa.agent.tool.api.ToolProvider;
import io.haifa.agent.tool.api.ToolProviderId;
import java.util.Objects;

public final class McpToolProvider implements ToolProvider {
    private final McpServerId serverId;
    private final ToolProviderId providerId;
    private final McpToolBindingStore bindings;
    private final McpConnectionManager connections;
    private final McpContentMapper contentMapper;

    public McpToolProvider(
            McpServerId serverId,
            McpToolBindingStore bindings,
            McpConnectionManager connections,
            McpContentMapper contentMapper) {
        this.serverId = Objects.requireNonNull(serverId, "serverId");
        this.providerId = new ToolProviderId("mcp." + serverId.value());
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.contentMapper = Objects.requireNonNull(contentMapper, "contentMapper");
    }

    @Override
    public ToolProviderId id() {
        return providerId;
    }

    @Override
    public ToolResult invoke(ToolInvocationRequest request) {
        request.cancellation().throwIfCancellationRequested();
        var binding = bindings.find(request.binding().providerBindingReference())
                .orElseThrow(() -> failure("MCP_BINDING_MISSING", "frozen MCP binding is unavailable"));
        var server = connections.definition(serverId);
        if (!binding.serverId().equals(serverId.value())
                || !binding.serverBindingVersion().equals(server.bindingVersion())
                || !binding.serverBindingDigest().equals(server.bindingDigest())
                || !binding.targetProtocolVersion().equals(McpProtocolProfile.VERSION_2025_11_25)
                || !binding.negotiatedProtocolVersion().equals(McpProtocolProfile.VERSION_2025_11_25)
                || !binding.transportIdentityReference()
                        .equals(server.transport().identityReference())
                || !binding.localDefinitionHash()
                        .equals(request.binding().coordinate().definitionHash())
                || !request.binding().definition().providerId().equals(providerId)) {
            throw failure("MCP_BINDING_DRIFT", "frozen MCP binding failed integrity validation");
        }
        McpConnection connection =
                connections.acquire(serverId, request.tenant(), request.principal(), request.credentialLeases());
        request.cancellation().throwIfCancellationRequested();
        try {
            var remoteResult = connection
                    .client()
                    .callTool(
                            binding.remoteToolName(),
                            request.arguments().values(),
                            request.credentialLeases(),
                            request.observer());
            request.observer().acknowledged();
            return contentMapper.map(remoteResult);
        } catch (ToolInvocationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ToolInvocationException(
                    "MCP_CALL_OUTCOME_UNKNOWN",
                    ToolDispatchState.OUTCOME_UNKNOWN,
                    "MCP tool call outcome is unknown",
                    exception);
        } finally {
            if (server.transport() instanceof StdioDefinition) connections.invalidate(connection);
        }
    }

    private static ToolInvocationException failure(String code, String message) {
        return new ToolInvocationException(code, ToolDispatchState.NOT_DISPATCHED, message);
    }
}
