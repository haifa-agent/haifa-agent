package io.haifa.agent.mcp.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.credential.api.CredentialLease;
import io.haifa.agent.mcp.config.McpProtocolProfile;
import io.haifa.agent.mcp.config.McpServerDefinition;
import io.haifa.agent.mcp.protocol.McpListToolsPage;
import io.haifa.agent.mcp.protocol.McpRemoteContent;
import io.haifa.agent.mcp.protocol.McpRemoteTool;
import io.haifa.agent.mcp.protocol.McpRemoteToolResult;
import io.haifa.agent.mcp.transport.http.McpHttpResponseLimitException;
import io.haifa.agent.tool.api.ToolDispatchState;
import io.haifa.agent.tool.api.ToolInvocationException;
import io.haifa.agent.tool.api.ToolInvocationObserver;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ModernMcpClientFacade implements McpClientFacade {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModernMcpClientFacade.class);
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {};
    private static final Pattern HEADER_TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    private final McpServerDefinition server;
    private final ModernMcpTransport transport;
    private final ObjectMapper mapper;
    private final McpTelemetry telemetry;
    private final AtomicReference<McpConnectionState> state = new AtomicReference<>(McpConnectionState.DISCONNECTED);
    private final Map<String, List<ParameterHeader>> parameterHeaders = new ConcurrentHashMap<>();
    private volatile McpServerSnapshot snapshot;

    ModernMcpClientFacade(
            McpServerDefinition server, ModernMcpTransport transport, ObjectMapper mapper, McpTelemetry telemetry) {
        this.server = server;
        this.transport = transport;
        this.mapper = mapper;
        this.telemetry = telemetry;
        telemetry.stateChanged(server.serverId(), McpConnectionState.DISCONNECTED);
    }

    @Override
    public synchronized McpServerSnapshot initialize(List<CredentialLease> credentials) {
        if (state.get() == McpConnectionState.READY) return snapshot;
        if (!state.compareAndSet(McpConnectionState.DISCONNECTED, McpConnectionState.CONNECTING)) {
            throw new IllegalStateException("MCP connection cannot initialize from " + state.get());
        }
        telemetry.stateChanged(server.serverId(), McpConnectionState.CONNECTING);
        transition(McpConnectionState.INITIALIZING);
        try {
            Map<String, Object> result = transport.request(
                    "server/discover", Map.of(), Map.of(), credentials, ToolInvocationObserver.noop());
            requireComplete(result);
            List<String> supported =
                    mapper.convertValue(result.getOrDefault("supportedVersions", List.of()), new TypeReference<>() {});
            if (!supported.contains(server.protocol().targetVersion())) {
                Optional<String> future = supported.stream()
                        .filter(McpProtocolProfile::isFutureVersion)
                        .min(String::compareTo);
                if (future.isPresent()) {
                    throw pendingAdaptation(future.orElseThrow(), ToolDispatchState.ACKNOWLEDGED);
                }
                throw new ToolInvocationException(
                        "MCP_PROTOCOL_VERSION_MISMATCH",
                        ToolDispatchState.ACKNOWLEDGED,
                        "MCP server does not support the configured protocol version");
            }
            Map<String, Object> capabilities = objectMap(result.get("capabilities"));
            if (!capabilities.containsKey("tools")) {
                throw new ToolInvocationException(
                        "MCP_TOOLS_CAPABILITY_MISSING",
                        ToolDispatchState.ACKNOWLEDGED,
                        "MCP server does not declare tools capability");
            }
            Map<String, Object> metadata = objectMap(result.get("_meta"));
            Map<String, Object> serverInfo = objectMap(metadata.get("io.modelcontextprotocol/serverInfo"));
            snapshot = new McpServerSnapshot(
                    server.serverId(),
                    server.bindingReference(),
                    server.bindingDigest(),
                    server.protocol().targetVersion(),
                    server.protocol().targetVersion(),
                    string(serverInfo.get("name"), "unknown-server"),
                    string(serverInfo.get("version"), "unknown"),
                    true,
                    booleanValue(objectMap(capabilities.get("tools")).get("listChanged")),
                    capabilities.containsKey("resources"),
                    capabilities.containsKey("prompts"));
            transition(McpConnectionState.READY);
            return snapshot;
        } catch (RuntimeException exception) {
            transition(McpConnectionState.FAILED);
            close();
            throw mapFailure("MCP_INITIALIZE_FAILED", exception);
        }
    }

    @Override
    public McpListToolsPage listTools(String cursor, List<CredentialLease> credentials) {
        requireReady();
        try {
            Map<String, Object> params = cursor == null ? Map.of() : Map.of("cursor", cursor);
            Map<String, Object> result =
                    transport.request("tools/list", params, Map.of(), credentials, ToolInvocationObserver.noop());
            requireComplete(result);
            List<Map<String, Object>> documents =
                    mapper.convertValue(result.getOrDefault("tools", List.of()), new TypeReference<>() {});
            List<McpRemoteTool> tools = new ArrayList<>();
            for (Map<String, Object> document : documents) {
                mapTool(document).ifPresent(tools::add);
            }
            return new McpListToolsPage(tools, Optional.ofNullable(stringOrNull(result.get("nextCursor"))));
        } catch (RuntimeException exception) {
            throw mapFailure("MCP_LIST_FAILED", exception);
        }
    }

    @Override
    public McpRemoteToolResult callTool(
            String name,
            Map<String, Object> arguments,
            List<CredentialLease> credentials,
            ToolInvocationObserver observer) {
        requireReady();
        try {
            if (!parameterHeaders.containsKey(name)) {
                throw new ToolInvocationException(
                        "MCP_TOOL_SCHEMA_NOT_DISCOVERED",
                        ToolDispatchState.NOT_DISPATCHED,
                        "MCP tool must be discovered before it can be called");
            }
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Mcp-Name", encodeHeaderValue(name));
            for (ParameterHeader mapping : parameterHeaders.getOrDefault(name, List.of())) {
                Object value = mapping.value(arguments);
                if (value != null) headers.put("Mcp-Param-" + mapping.name(), encodeParameterValue(value));
            }
            Map<String, Object> result = transport.request(
                    "tools/call", Map.of("name", name, "arguments", arguments), headers, credentials, observer);
            requireComplete(result);
            List<Map<String, Object>> content =
                    mapper.convertValue(result.getOrDefault("content", List.of()), new TypeReference<>() {});
            return new McpRemoteToolResult(
                    booleanValue(result.get("isError")),
                    content.stream().map(this::mapContent).toList(),
                    structured(result.get("structuredContent")));
        } catch (RuntimeException exception) {
            throw mapFailure("MCP_CALL_FAILED", exception);
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
            transport.close();
        } finally {
            transition(McpConnectionState.DISCONNECTED);
        }
    }

    private Optional<McpRemoteTool> mapTool(Map<String, Object> document) {
        String name = string(document.get("name"), "");
        Map<String, Object> inputSchema = objectMap(document.get("inputSchema"));
        Optional<List<ParameterHeader>> mappings = parameterHeaders(inputSchema);
        if (mappings.isEmpty()) {
            parameterHeaders.remove(name);
            LOGGER.warn("Ignoring MCP tool {} because its x-mcp-header declaration is invalid", name);
            return Optional.empty();
        }
        parameterHeaders.put(name, mappings.orElseThrow());
        return Optional.of(new McpRemoteTool(
                name,
                stringOrNull(document.get("title")),
                stringOrNull(document.get("description")),
                inputSchema,
                objectMap(document.get("outputSchema")),
                objectMap(document.get("annotations")),
                objectMap(document.get("_meta"))));
    }

    private Optional<List<ParameterHeader>> parameterHeaders(Map<String, Object> schema) {
        List<ParameterHeader> mappings = new ArrayList<>();
        if (!collectHeaders(schema, List.of(), false, mappings)) return Optional.empty();
        Set<String> names = new HashSet<>();
        if (mappings.stream().anyMatch(mapping -> !names.add(mapping.name().toLowerCase(Locale.ROOT)))) {
            return Optional.empty();
        }
        return Optional.of(List.copyOf(mappings));
    }

    private boolean collectHeaders(
            Map<String, Object> node, List<String> path, boolean propertyNode, List<ParameterHeader> mappings) {
        if (node.containsKey("x-mcp-header")) {
            String header = string(node.get("x-mcp-header"), "");
            String type = string(node.get("type"), "");
            if (!propertyNode
                    || header.isEmpty()
                    || !HEADER_TOKEN.matcher(header).matches()
                    || !(type.equals("string") || type.equals("integer") || type.equals("boolean"))) {
                return false;
            }
            mappings.add(new ParameterHeader(header, path));
        }
        Map<String, Object> properties = objectMap(node.get("properties"));
        for (Map.Entry<String, Object> property : properties.entrySet()) {
            List<String> childPath = new ArrayList<>(path);
            childPath.add(property.getKey());
            if (!collectHeaders(objectMap(property.getValue()), childPath, true, mappings)) return false;
        }
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            if (entry.getKey().equals("properties") || entry.getKey().equals("x-mcp-header")) continue;
            if (containsHeaderOutsideProperties(entry.getValue())) return false;
        }
        return true;
    }

    private boolean containsHeaderOutsideProperties(Object value) {
        if (value instanceof Map<?, ?> map) {
            if (map.containsKey("x-mcp-header")) return true;
            return map.values().stream().anyMatch(this::containsHeaderOutsideProperties);
        }
        if (value instanceof Iterable<?> values) {
            for (Object item : values) if (containsHeaderOutsideProperties(item)) return true;
        }
        return false;
    }

    private McpRemoteContent mapContent(Map<String, Object> content) {
        String type = string(content.get("type"), "");
        return switch (type) {
            case "text" ->
                new McpRemoteContent(McpRemoteContent.Kind.TEXT, string(content.get("text"), ""), "text/plain", 0);
            case "image" -> encodedContent(McpRemoteContent.Kind.IMAGE, content);
            case "audio" -> encodedContent(McpRemoteContent.Kind.AUDIO, content);
            case "resource" -> new McpRemoteContent(McpRemoteContent.Kind.EMBEDDED_RESOURCE, "", "", 0);
            case "resource_link" -> new McpRemoteContent(McpRemoteContent.Kind.RESOURCE_LINK, "", "", 0);
            default -> new McpRemoteContent(McpRemoteContent.Kind.UNSUPPORTED, "", "", 0);
        };
    }

    private McpRemoteContent encodedContent(McpRemoteContent.Kind kind, Map<String, Object> content) {
        String data = string(content.get("data"), "");
        return new McpRemoteContent(kind, data, string(content.get("mimeType"), ""), data.length());
    }

    private void requireReady() {
        if (state.get() != McpConnectionState.READY) {
            throw new ToolInvocationException(
                    "MCP_NOT_READY", ToolDispatchState.NOT_DISPATCHED, "MCP connection is not ready");
        }
    }

    private static void requireComplete(Map<String, Object> result) {
        String resultType = string(result.get("resultType"), "");
        if (resultType.equals("complete")) return;
        String code = resultType.equals("input_required") ? "MCP_INPUT_REQUIRED_UNSUPPORTED" : "MCP_RESULT_UNSUPPORTED";
        throw new ToolInvocationException(
                code, ToolDispatchState.ACKNOWLEDGED, "MCP result requires unsupported interaction");
    }

    private ToolInvocationException mapFailure(String defaultCode, RuntimeException exception) {
        if (exception instanceof ToolInvocationException invocation) {
            telemetry.operationFailed(server.serverId(), invocation.failureCode());
            return invocation;
        }
        McpHttpResponseLimitException limit = find(exception, McpHttpResponseLimitException.class);
        if (limit != null) {
            telemetry.operationFailed(server.serverId(), limit.failureCode());
            return new ToolInvocationException(
                    limit.failureCode(),
                    ToolDispatchState.OUTCOME_UNKNOWN,
                    "MCP HTTP response exceeded a configured budget",
                    exception);
        }
        telemetry.operationFailed(server.serverId(), defaultCode);
        return new ToolInvocationException(
                defaultCode, ToolDispatchState.OUTCOME_UNKNOWN, "MCP operation failed", exception);
    }

    private static ToolInvocationException pendingAdaptation(String version, ToolDispatchState dispatchState) {
        return new ToolInvocationException(
                "MCP_PROTOCOL_VERSION_PENDING_ADAPTATION", dispatchState, McpProtocolProfile.adaptationNotice(version));
    }

    private void transition(McpConnectionState target) {
        state.set(target);
        telemetry.stateChanged(server.serverId(), target);
    }

    private Map<String, Object> objectMap(Object value) {
        return value == null ? Map.of() : mapper.convertValue(value, OBJECT_MAP);
    }

    private Map<String, Object> structured(Object value) {
        if (value == null) return Map.of();
        if (value instanceof Map<?, ?>) return objectMap(value);
        return Map.of("value", mapper.convertValue(value, Object.class));
    }

    private static String string(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean booleanValue(Object value) {
        return Boolean.TRUE.equals(value);
    }

    private static String encodeParameterValue(Object value) {
        if (value instanceof String string) return encodeHeaderValue(string);
        if (value instanceof Boolean bool) return bool.toString();
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            long integer = ((Number) value).longValue();
            if (integer < -MAX_SAFE_INTEGER || integer > MAX_SAFE_INTEGER) {
                throw new ToolInvocationException(
                        "MCP_HEADER_VALUE_INVALID",
                        ToolDispatchState.NOT_DISPATCHED,
                        "MCP header parameter integer exceeds the protocol safe range");
            }
            return Long.toString(integer);
        }
        throw new ToolInvocationException(
                "MCP_HEADER_VALUE_INVALID",
                ToolDispatchState.NOT_DISPATCHED,
                "MCP header parameter has an unsupported value type");
    }

    private static String encodeHeaderValue(String value) {
        boolean sentinel = value.startsWith("=?base64?") && value.endsWith("?=");
        boolean safe = !value.isEmpty()
                && value.equals(value.strip())
                && value.chars().allMatch(character -> character >= 0x20 && character <= 0x7e)
                && !sentinel;
        if (safe) return value;
        return "=?base64?" + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)) + "?=";
    }

    private static <T extends Throwable> T find(Throwable error, Class<T> type) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (type.isInstance(current)) return type.cast(current);
        }
        return null;
    }

    private record ParameterHeader(String name, List<String> path) {
        private ParameterHeader {
            path = List.copyOf(path);
        }

        private Object value(Map<String, Object> arguments) {
            Object current = arguments;
            for (String component : path) {
                if (!(current instanceof Map<?, ?> map) || !map.containsKey(component)) return null;
                current = map.get(component);
            }
            return current;
        }
    }
}
