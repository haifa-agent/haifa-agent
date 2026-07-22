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
import io.haifa.agent.mcp.transport.http.McpHttpResponseLimitException;
import io.haifa.agent.tool.api.ToolDispatchState;
import io.haifa.agent.tool.api.ToolInvocationException;
import io.haifa.agent.tool.api.ToolInvocationObserver;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
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
    private final TrackingMcpClientTransport transportFailures;
    private final AtomicReference<McpConnectionState> state = new AtomicReference<>(McpConnectionState.DISCONNECTED);

    SdkMcpClientFacade(
            McpServerDefinition server,
            McpSyncClient client,
            McpRequestContext credentials,
            ObjectMapper mapper,
            McpTelemetry telemetry,
            TrackingMcpClientTransport transportFailures) {
        this.server = server;
        this.client = client;
        this.credentials = credentials;
        this.mapper = mapper;
        this.telemetry = telemetry;
        this.transportFailures = transportFailures;
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
            transportFailures.clearFailure();
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
            throw mapOperationalFailure("MCP_INITIALIZE_FAILED", exception);
        }
    }

    @Override
    public McpListToolsPage listTools(String cursor, List<CredentialLease> leases) {
        requireReady();
        try {
            transportFailures.clearFailure();
            McpSchema.ListToolsResult result = credentials.withCredentials(
                    leases, () -> client.listTools(cursor == null ? McpSchema.FIRST_PAGE : cursor));
            List<McpRemoteTool> tools =
                    result.tools().stream().map(this::mapTool).toList();
            return new McpListToolsPage(tools, Optional.ofNullable(result.nextCursor()));
        } catch (RuntimeException exception) {
            throw mapOperationalFailure("MCP_LIST_FAILED", exception);
        }
    }

    @Override
    public McpRemoteToolResult callTool(
            String name, Map<String, Object> arguments, List<CredentialLease> leases, ToolInvocationObserver observer) {
        requireReady();
        try {
            transportFailures.clearFailure();
            McpSchema.CallToolResult result = credentials.withInvocation(
                    leases, observer, () -> client.callTool(new McpSchema.CallToolRequest(name, arguments)));
            return new McpRemoteToolResult(
                    Boolean.TRUE.equals(result.isError()),
                    result.content().stream().map(this::mapContent).toList(),
                    structured(result.structuredContent()));
        } catch (RuntimeException exception) {
            throw mapOperationalFailure("MCP_CALL_FAILED", exception);
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

    private ToolInvocationException mapOperationalFailure(String defaultCode, RuntimeException exception) {
        Throwable transportFailure = transportFailures.consumeFailure().orElse(null);
        McpHttpClientTransportAuthorizationException authorization =
                find(exception, McpHttpClientTransportAuthorizationException.class);
        if (authorization == null) {
            authorization = find(transportFailure, McpHttpClientTransportAuthorizationException.class);
        }
        if (authorization != null) {
            String code = server.discoveryCredentials().isEmpty() ? "MCP_AUTH_FLOW_UNSUPPORTED" : "MCP_REAUTH_REQUIRED";
            telemetry.operationFailed(server.serverId(), code);
            return new ToolInvocationException(
                    code,
                    ToolDispatchState.ACKNOWLEDGED,
                    server.discoveryCredentials().isEmpty()
                            ? "MCP server requires an unsupported authorization flow"
                            : "MCP server rejected the configured credential");
        }
        if (isSessionInvalid(exception) || isSessionInvalid(transportFailure)) {
            transition(McpConnectionState.FAILED);
            close();
            telemetry.operationFailed(server.serverId(), "MCP_SESSION_INVALID");
            return new ToolInvocationException(
                    "MCP_SESSION_INVALID",
                    ToolDispatchState.OUTCOME_UNKNOWN,
                    "MCP server invalidated the current session",
                    exception);
        }
        McpHttpResponseLimitException limit = find(exception, McpHttpResponseLimitException.class);
        if (limit == null) limit = find(transportFailure, McpHttpResponseLimitException.class);
        String aggregate = io.modelcontextprotocol.spec.McpError.aggregateExceptionMessages(exception);
        if (limit == null && aggregate.contains("MCP HTTP response body exceeded configured budget")) {
            limit = new McpHttpResponseLimitException(
                    "MCP_HTTP_RESPONSE_BODY_TOO_LARGE", "MCP HTTP response body exceeded configured budget");
        }
        if (limit == null && aggregate.contains("MCP HTTP response headers exceeded configured budget")) {
            limit = new McpHttpResponseLimitException(
                    "MCP_HTTP_RESPONSE_HEADERS_TOO_LARGE", "MCP HTTP response headers exceeded configured budget");
        }
        if (limit != null) {
            telemetry.operationFailed(server.serverId(), limit.failureCode());
            return new ToolInvocationException(
                    limit.failureCode(),
                    ToolDispatchState.OUTCOME_UNKNOWN,
                    "MCP HTTP response exceeded a configured budget",
                    exception);
        }
        return mapFailure(defaultCode, ToolDispatchState.OUTCOME_UNKNOWN, exception);
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

    private static boolean contains(Throwable error, Class<? extends Throwable> type) {
        return find(error, type) != null;
    }

    private static boolean isSessionInvalid(Throwable error) {
        return contains(error, io.modelcontextprotocol.spec.McpTransportSessionNotFoundException.class)
                || messages(error).contains("session not found")
                || messages(error).contains("session id") && messages(error).contains("404");
    }

    private static <T extends Throwable> T find(Throwable error, Class<T> type) {
        return find(error, type, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
    }

    private static <T extends Throwable> T find(Throwable error, Class<T> type, java.util.Set<Throwable> visited) {
        if (error == null || !visited.add(error)) return null;
        if (type.isInstance(error)) return type.cast(error);
        T cause = find(error.getCause(), type, visited);
        if (cause != null) return cause;
        for (Throwable suppressed : error.getSuppressed()) {
            T match = find(suppressed, type, visited);
            if (match != null) return match;
        }
        return null;
    }

    private static String messages(Throwable error) {
        var result = new StringBuilder();
        collectMessages(error, result, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
        return result.toString().toLowerCase(java.util.Locale.ROOT);
    }

    private static void collectMessages(Throwable error, StringBuilder target, java.util.Set<Throwable> visited) {
        if (error == null || !visited.add(error)) return;
        if (error.getMessage() != null) target.append(' ').append(error.getMessage());
        collectMessages(error.getCause(), target, visited);
        for (Throwable suppressed : error.getSuppressed()) collectMessages(suppressed, target, visited);
    }
}
