package io.haifa.agent.mcp.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.mcp.config.McpProtocolProfile;
import io.haifa.agent.mcp.config.McpServerDefinition;
import io.haifa.agent.mcp.config.StreamableHttpDefinition;
import io.haifa.agent.mcp.transport.http.BoundedHttpClientBuilder;
import io.haifa.agent.mcp.transport.http.McpHttpCredentialContext;
import io.haifa.agent.tool.api.ToolDispatchState;
import io.haifa.agent.tool.api.ToolInvocationException;
import io.haifa.agent.tool.api.ToolInvocationObserver;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

final class ModernHttpMcpTransport implements ModernMcpTransport {
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {};
    private final McpServerDefinition server;
    private final StreamableHttpDefinition definition;
    private final ObjectMapper mapper;
    private final McpHttpCredentialContext credentialContext;
    private final HttpClient client;
    private final AtomicLong requestIds = new AtomicLong();

    ModernHttpMcpTransport(McpServerDefinition server, StreamableHttpDefinition definition, ObjectMapper mapper) {
        this.server = server;
        this.definition = definition;
        this.mapper = mapper;
        this.credentialContext = new McpHttpCredentialContext(
                server.discoveryCredentials(), StreamableHttpDefinition.origin(definition.endpoint()));
        this.client = new BoundedHttpClientBuilder(
                        HttpClient.newBuilder()
                                .connectTimeout(definition.connectTimeout())
                                .followRedirects(HttpClient.Redirect.NEVER),
                        definition.maxBodyBytes(),
                        definition.maxHeaderBytes(),
                        0,
                        Duration.ZERO,
                        java.util.Set.of())
                .build();
    }

    @Override
    public Map<String, Object> request(
            String method,
            Map<String, Object> parameters,
            Map<String, String> envelopeHeaders,
            Map<String, String> credentials,
            ToolInvocationObserver observer) {
        return credentialContext.withInvocation(
                credentials, observer, () -> send(method, parameters, envelopeHeaders, credentialContext.snapshot()));
    }

    private Map<String, Object> send(
            String method,
            Map<String, Object> parameters,
            Map<String, String> envelopeHeaders,
            io.modelcontextprotocol.common.McpTransportContext context) {
        long id = requestIds.incrementAndGet();
        Map<String, Object> params = new LinkedHashMap<>(parameters);
        params.put(
                "_meta",
                Map.of(
                        "io.modelcontextprotocol/protocolVersion", McpProtocolProfile.VERSION_2026_07_28,
                        "io.modelcontextprotocol/clientInfo", Map.of("name", "haifa-agent", "version", "0.1.0"),
                        "io.modelcontextprotocol/clientCapabilities", Map.of()));
        byte[] body;
        try {
            body = mapper.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
        } catch (IOException exception) {
            throw new IllegalStateException("failed to encode MCP request", exception);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(definition.endpoint())
                .timeout(definition.requestTimeout())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", McpProtocolProfile.VERSION_2026_07_28)
                .header("Mcp-Method", method)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        envelopeHeaders.forEach(builder::header);
        credentialContext.customize(
                builder, "POST", definition.endpoint(), new String(body, StandardCharsets.UTF_8), context);
        try {
            HttpResponse<String> response =
                    client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return decode(response, id);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("MCP HTTP request interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("MCP HTTP request failed", exception);
        }
    }

    private Map<String, Object> decode(HttpResponse<String> response, long id) {
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            String code = server.discoveryCredentials().isEmpty() ? "MCP_AUTH_FLOW_UNSUPPORTED" : "MCP_REAUTH_REQUIRED";
            throw new ToolInvocationException(
                    code,
                    ToolDispatchState.ACKNOWLEDGED,
                    server.discoveryCredentials().isEmpty()
                            ? "MCP server requires an unsupported authorization flow"
                            : "MCP server rejected the configured credential");
        }
        if (status < 200 || status >= 300) {
            var future = McpProtocolProfile.findFutureProtocolVersion(response.body());
            if (future.isPresent()) {
                throw new ToolInvocationException(
                        "MCP_PROTOCOL_VERSION_PENDING_ADAPTATION",
                        ToolDispatchState.ACKNOWLEDGED,
                        McpProtocolProfile.adaptationNotice(future.orElseThrow()));
            }
            String description = response.body().toLowerCase(Locale.ROOT);
            String code = status == 400 && description.contains("protocol")
                    ? "MCP_PROTOCOL_VERSION_MISMATCH"
                    : "MCP_HTTP_REJECTED";
            throw new ToolInvocationException(code, ToolDispatchState.ACKNOWLEDGED, "MCP server rejected the request");
        }
        String contentType =
                response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        if (!contentType.contains("application/json") && !contentType.contains("text/event-stream")) {
            throw new IllegalStateException("MCP HTTP response has an unsupported content type");
        }
        Map<String, Object> message = parseMessage(response, contentType);
        if (!String.valueOf(id).equals(String.valueOf(message.get("id")))) {
            throw new IllegalStateException("MCP response id does not match the request");
        }
        if (message.containsKey("error")) {
            throw new ToolInvocationException(
                    "MCP_PROTOCOL_ERROR", ToolDispatchState.ACKNOWLEDGED, "MCP server returned a protocol error");
        }
        Map<String, Object> result = objectMap(message.get("result"));
        if (result.isEmpty() && !message.containsKey("result")) {
            throw new IllegalStateException("MCP response does not contain a result");
        }
        return result;
    }

    private Map<String, Object> parseMessage(HttpResponse<String> response, String contentType) {
        String body = response.body();
        try {
            if (!contentType.contains("text/event-stream")) return mapper.readValue(body, OBJECT_MAP);
            Map<String, Object> last = null;
            for (String line : body.split("\\R")) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).stripLeading();
                if (!data.isEmpty()) last = mapper.readValue(data, OBJECT_MAP);
            }
            if (last == null) throw new IllegalStateException("MCP SSE response did not contain JSON-RPC data");
            return last;
        } catch (IOException exception) {
            throw new IllegalStateException("MCP response is not valid JSON-RPC", exception);
        }
    }

    private Map<String, Object> objectMap(Object value) {
        return value == null ? Map.of() : mapper.convertValue(value, OBJECT_MAP);
    }

    @Override
    public void close() {}
}
