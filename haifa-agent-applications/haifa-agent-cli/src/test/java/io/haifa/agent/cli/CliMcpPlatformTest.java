package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import io.haifa.agent.tool.core.DefaultToolInvoker;
import io.haifa.agent.tool.core.ToolCatalogBuilder;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class CliMcpPlatformTest {
    @Test
    void discoversAndInvokesAllowlistedHttpTool() throws Exception {
        try (StubMcpServer stub = new StubMcpServer();
                CliMcpPlatform platform =
                        CliMcpPlatform.connect(List.of(configuration(stub)), new PrincipalRef("local-user", "user"))) {
            assertThat(platform.contributions()).singleElement().satisfies(contribution -> assertThat(
                            contribution.alias().value())
                    .isEqualTo("utility_time_now"));
            var contribution = platform.contributions().getFirst();
            var catalog = new ToolCatalogBuilder()
                    .register(
                            contribution.alias(),
                            contribution.definition(),
                            contribution.providerBindingReference(),
                            contribution.provider())
                    .freeze();
            var binding = catalog.findByAlias(contribution.alias()).orElseThrow();
            var result = new DefaultToolInvoker(catalog)
                    .invoke(new ToolInvocationRequest(
                            binding,
                            new ToolCallId("call-1"),
                            new AgentRunId("run-1"),
                            new TenantRef("local"),
                            new PrincipalRef("local-user", "user"),
                            new ToolArguments(
                                    binding.definition().inputSchema().id(),
                                    binding.definition().inputSchema().version(),
                                    Map.of("timezone", "UTC")),
                            Instant.now().plusSeconds(10),
                            Optional.empty(),
                            () -> false,
                            List.of()));

            assertThat(result.successful()).isTrue();
            assertThat(result.structuredData()).containsEntry("timezone", "UTC");
            assertThat(stub.methods()).contains("initialize", "notifications/initialized", "tools/list", "tools/call");
        }
    }

    private static CliConfiguration.McpServer configuration(StubMcpServer stub) {
        return new CliConfiguration.McpServer(
                "utility",
                "Utility MCP",
                stub.endpoint(),
                true,
                Set.of("time_now"),
                "utility",
                "utility",
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                Duration.ofSeconds(10),
                1024 * 1024,
                16 * 1024,
                1);
    }

    private static final class StubMcpServer implements AutoCloseable {
        private final ObjectMapper mapper = new ObjectMapper();
        private final HttpServer server;
        private final List<String> methods = new CopyOnWriteArrayList<>();

        private StubMcpServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/mcp", this::handle);
            server.start();
        }

        private java.net.URI endpoint() {
            return java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
        }

        private List<String> methods() {
            return List.copyOf(methods);
        }

        private void handle(HttpExchange exchange) throws IOException {
            if ("GET".equals(exchange.getRequestMethod()) || "DELETE".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            Map<String, Object> request = mapper.readValue(exchange.getRequestBody(), new TypeReference<>() {});
            String method = String.valueOf(request.get("method"));
            methods.add(method);
            if (!request.containsKey("id")) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            Object result =
                    switch (method) {
                        case "initialize" ->
                            Map.of(
                                    "protocolVersion", "2025-11-25",
                                    "capabilities", Map.of("tools", Map.of("listChanged", false)),
                                    "serverInfo", Map.of("name", "cli-test", "version", "1.0.0"));
                        case "tools/list" ->
                            Map.of(
                                    "tools",
                                    List.of(Map.of(
                                            "name", "time_now",
                                            "title", "Current time",
                                            "description", "Return the current time",
                                            "inputSchema",
                                                    Map.of(
                                                            "type",
                                                            "object",
                                                            "properties",
                                                            Map.of("timezone", Map.of("type", "string")),
                                                            "required",
                                                            List.of("timezone"),
                                                            "additionalProperties",
                                                            false),
                                            "outputSchema", Map.of("type", "object", "additionalProperties", true))));
                        case "tools/call" ->
                            Map.of(
                                    "content", List.of(Map.of("type", "text", "text", "UTC")),
                                    "structuredContent", Map.of("timezone", "UTC"),
                                    "isError", false);
                        default -> throw new IllegalStateException("unexpected method " + method);
                    };
            byte[] response = mapper.writeValueAsString(
                            Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", result))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
