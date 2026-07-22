package io.haifa.agent.mcp.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.credential.api.CredentialLease;
import io.haifa.agent.mcp.config.McpProtocolProfile;
import io.haifa.agent.mcp.config.McpServerDefinition;
import io.haifa.agent.mcp.internal.McpRequestContext;
import io.haifa.agent.mcp.protocol.McpListToolsPage;
import io.haifa.agent.mcp.protocol.McpRemoteContent;
import io.haifa.agent.mcp.protocol.McpRemoteTool;
import io.haifa.agent.mcp.protocol.McpRemoteToolResult;
import io.haifa.agent.tool.api.ToolDispatchState;
import io.haifa.agent.tool.api.ToolInvocationException;
import io.haifa.agent.tool.api.ToolInvocationObserver;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

final class SdkMcpClientFacade implements McpClientFacade {
    private final McpServerDefinition server;
    private final McpSyncClient client;
    private final McpRequestContext credentials;
    private final ObjectMapper mapper;
    private final McpTelemetry telemetry;
    private final AtomicReference<McpConnectionState> state = new AtomicReference<>(McpConnectionState.DISCONNECTED);

    SdkMcpClientFacade(
            McpServerDefinition server,
            McpSyncClient client,
            McpRequestContext credentials,
            ObjectMapper mapper,
            McpTelemetry telemetry) {
        this.server = server;
        this.client = client;
        this.credentials = credentials;
        this.mapper = mapper;
        this.telemetry = telemetry;
        telemetry.stateChanged(server.serverId(), McpConnectionState.DISCONNECTED);
    }

    @Override
    public synchronized McpServerSnapshot initialize(List<CredentialLease> leases) {
        if (state.get() == McpConnectionState.READY) return snapshot(client.getCurrentInitializationResult());
        if (!state.compareAndSet(McpConnectionState.DISCONNECTED, McpConnectionState.CONNECTING)) {
            throw new IllegalStateException("MCP connection cannot initialize from " + state.get());
        }
        telemetry.stateChanged(server.serverId(), McpConnectionState.CONNECTING);
        transition(McpConnectionState.INITIALIZING);
        try {
            McpSchema.InitializeResult result = credentials.withCredentials(leases, client::initialize);
            if (!McpProtocolProfile.VERSION_2025_11_25.equals(result.protocolVersion())) {
                throw new ToolInvocationException(
                        "MCP_PROTOCOL_VERSION_MISMATCH",
                        ToolDispatchState.ACKNOWLEDGED,
                        "MCP server negotiated an unsupported protocol version");
            }
            if (result.capabilities().tools() == null) {
                throw new ToolInvocationException(
                        "MCP_TOOLS_CAPABILITY_MISSING",
                        ToolDispatchState.ACKNOWLEDGED,
                        "MCP server does not declare tools capability");
            }
            transition(McpConnectionState.READY);
            return snapshot(result);
        } catch (RuntimeException exception) {
            transition(McpConnectionState.FAILED);
            close();
            throw mapFailure("MCP_INITIALIZE_FAILED", ToolDispatchState.OUTCOME_UNKNOWN, exception);
        }
    }

    @Override
    public McpListToolsPage listTools(String cursor, List<CredentialLease> leases) {
        requireReady();
        try {
            McpSchema.ListToolsResult result = credentials.withCredentials(
                    leases, () -> client.listTools(cursor == null ? McpSchema.FIRST_PAGE : cursor));
            List<McpRemoteTool> tools =
                    result.tools().stream().map(this::mapTool).toList();
            return new McpListToolsPage(tools, Optional.ofNullable(result.nextCursor()));
        } catch (RuntimeException exception) {
            throw mapFailure("MCP_LIST_FAILED", ToolDispatchState.OUTCOME_UNKNOWN, exception);
        }
    }

    @Override
    public McpRemoteToolResult callTool(
            String name, Map<String, Object> arguments, List<CredentialLease> leases, ToolInvocationObserver observer) {
        requireReady();
        try {
            McpSchema.CallToolResult result = credentials.withInvocation(
                    leases, observer, () -> client.callTool(new McpSchema.CallToolRequest(name, arguments)));
            return new McpRemoteToolResult(
                    Boolean.TRUE.equals(result.isError()),
                    result.content().stream().map(this::mapContent).toList(),
                    structured(result.structuredContent()));
        } catch (RuntimeException exception) {
            throw mapFailure("MCP_CALL_FAILED", ToolDispatchState.OUTCOME_UNKNOWN, exception);
        }
    }

    @Override
    public McpConnectionState state() {
        return state.get();
    }

    @Override
    public synchronized void close() {
        McpConnectionState current = state.get();
        if (current == McpConnectionState.DISCONNECTED || current == McpConnectionState.CLOSING) return;
        transition(McpConnectionState.CLOSING);
        try {
            client.closeGracefully();
        } finally {
            transition(McpConnectionState.DISCONNECTED);
        }
    }

    private McpServerSnapshot snapshot(McpSchema.InitializeResult result) {
        var capabilities = result.capabilities();
        var info = result.serverInfo();
        return new McpServerSnapshot(
                server.serverId(),
                server.bindingReference(),
                server.bindingDigest(),
                server.protocol().targetVersion(),
                result.protocolVersion(),
                info.name(),
                info.version(),
                capabilities.tools() != null,
                capabilities.tools() != null
                        && Boolean.TRUE.equals(capabilities.tools().listChanged()),
                capabilities.resources() != null,
                capabilities.prompts() != null);
    }

    private McpRemoteTool mapTool(McpSchema.Tool tool) {
        return new McpRemoteTool(
                tool.name(),
                tool.title(),
                tool.description(),
                tool.inputSchema(),
                tool.outputSchema(),
                objectMap(tool.annotations()),
                tool.meta());
    }

    private McpRemoteContent mapContent(McpSchema.Content content) {
        if (content instanceof McpSchema.TextContent text) {
            return new McpRemoteContent(McpRemoteContent.Kind.TEXT, text.text(), "text/plain", 0);
        }
        if (content instanceof McpSchema.ImageContent image) {
            return new McpRemoteContent(
                    McpRemoteContent.Kind.IMAGE,
                    image.data(),
                    image.mimeType(),
                    image.data().length());
        }
        if (content instanceof McpSchema.AudioContent audio) {
            return new McpRemoteContent(
                    McpRemoteContent.Kind.AUDIO,
                    audio.data(),
                    audio.mimeType(),
                    audio.data().length());
        }
        if (content instanceof McpSchema.EmbeddedResource) {
            return new McpRemoteContent(McpRemoteContent.Kind.EMBEDDED_RESOURCE, "", "", 0);
        }
        if (content instanceof McpSchema.ResourceLink) {
            return new McpRemoteContent(McpRemoteContent.Kind.RESOURCE_LINK, "", "", 0);
        }
        return new McpRemoteContent(McpRemoteContent.Kind.UNSUPPORTED, "", "", 0);
    }

    private Map<String, Object> objectMap(Object value) {
        if (value == null) return Map.of();
        return mapper.convertValue(value, new TypeReference<>() {});
    }

    private Map<String, Object> structured(Object value) {
        if (value == null) return Map.of();
        if (value instanceof Map<?, ?>) return objectMap(value);
        return Map.of("value", mapper.convertValue(value, Object.class));
    }

    private void requireReady() {
        if (state.get() != McpConnectionState.READY) {
            throw new ToolInvocationException(
                    "MCP_NOT_READY", ToolDispatchState.NOT_DISPATCHED, "MCP connection is not ready");
        }
    }

    private ToolInvocationException mapFailure(
            String code, ToolDispatchState dispatchState, RuntimeException exception) {
        if (exception instanceof ToolInvocationException invocation) {
            telemetry.operationFailed(server.serverId(), invocation.failureCode());
            return invocation;
        }
        if (isProtocolVersionMismatch(exception)) {
            telemetry.operationFailed(server.serverId(), "MCP_PROTOCOL_VERSION_MISMATCH");
            return new ToolInvocationException(
                    "MCP_PROTOCOL_VERSION_MISMATCH",
                    ToolDispatchState.ACKNOWLEDGED,
                    "MCP server negotiated an unsupported protocol version",
                    exception);
        }
        telemetry.operationFailed(server.serverId(), code);
        return new ToolInvocationException(code, dispatchState, "MCP operation failed", exception);
    }

    private void transition(McpConnectionState target) {
        state.set(target);
        telemetry.stateChanged(server.serverId(), target);
    }

    private static boolean isProtocolVersionMismatch(Throwable exception) {
        if (io.modelcontextprotocol.spec.McpError.aggregateExceptionMessages(exception)
                .toLowerCase(java.util.Locale.ROOT)
                .contains("unsupported protocol version")) {
            return true;
        }
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof io.modelcontextprotocol.spec.McpError mcpError
                    && mcpError.getJsonRpcError() != null
                    && "Unsupported protocol version"
                            .equals(mcpError.getJsonRpcError().message())) {
                return true;
            }
            if (current.getMessage() != null
                    && current.getMessage()
                            .toLowerCase(java.util.Locale.ROOT)
                            .contains("unsupported protocol version")) {
                return true;
            }
        }
        return false;
    }
}
