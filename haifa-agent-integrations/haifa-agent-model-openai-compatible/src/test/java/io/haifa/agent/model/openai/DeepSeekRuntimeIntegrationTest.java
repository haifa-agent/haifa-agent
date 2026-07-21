package io.haifa.agent.model.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.model.api.ModelProviderDefinition;
import io.haifa.agent.model.api.ModelToolSpecification;
import io.haifa.agent.model.api.ResolvedCredential;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.RuntimeOverrides;
import io.haifa.agent.runtime.core.RuntimeCoreBuilder;
import io.haifa.agent.runtime.core.execution.ManualExecutionScheduler;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import io.haifa.agent.runtime.core.tool.ToolDefinition;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DeepSeekRuntimeIntegrationTest {
    @Test
    void realAdapterDrivesRuntimeToolLoopAgainstLocalStub() throws Exception {
        ObjectMapper json = new ObjectMapper();
        AtomicInteger calls = new AtomicInteger();
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> handle(exchange, json, calls, requests));
        server.start();
        try {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            ModelProviderDefinition defaults = DeepSeekDefaults.provider();
            ModelProviderDefinition provider = new ModelProviderDefinition(
                    defaults.id(),
                    defaults.displayName(),
                    defaults.adapterType(),
                    endpoint,
                    defaults.credentialRef(),
                    defaults.status(),
                    defaults.models(),
                    defaults.options(),
                    defaults.metadata());
            OpenAiCompatibleChatModel adapter = new OpenAiCompatibleChatModel(
                    provider,
                    HttpClient.newBuilder()
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .build(),
                    json,
                    ignored -> new ResolvedCredential("stub-key"),
                    true,
                    1024 * 1024);
            ManualExecutionScheduler scheduler = new ManualExecutionScheduler();
            InMemoryRuntimeStore store = new InMemoryRuntimeStore();
            AtomicInteger idSequence = new AtomicInteger();
            IdentifierGenerator ids = () -> "deepseek-it-" + idSequence.incrementAndGet();
            TimeProvider time = () -> Instant.parse("2026-07-21T00:00:00Z");
            var runtime = new RuntimeCoreBuilder()
                    .registerChatModel("openai-compatible", adapter)
                    .registerTool(new ToolDefinition("echo", "1.0", "echo.input", false))
                    .registerModelTool(new ModelToolSpecification(
                            "echo",
                            "1.0",
                            "Echo a text value",
                            "echo.input",
                            "1.0",
                            Map.of(
                                    "type", "object",
                                    "properties", Map.of("text", Map.of("type", "string")),
                                    "required", List.of("text")),
                            false))
                    .toolExecutor((run, definition, request) -> new ToolResult(
                            true,
                            "echoed: " + request.arguments().values().get("text"),
                            request.arguments().values(),
                            List.of(),
                            List.of(),
                            false))
                    .scheduler(scheduler)
                    .store(store)
                    .identifierGenerator(ids)
                    .timeProvider(time)
                    .build();

            var accepted = runtime.start(request());
            scheduler.runAll();

            assertThat(runtime.find(accepted.runId()).orElseThrow().status()).isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(runtime.find(accepted.runId()).orElseThrow().output()).contains("adapter done");
            assertThat(calls).hasValue(2);
            assertThat(requests).hasSize(2).allSatisfy(request -> {
                assertThat(request.path("model").asText()).isEqualTo("deepseek-v4-pro");
                assertThat(request.path("stream").asBoolean()).isFalse();
                assertThat(request.path("thinking").path("type").asText()).isEqualTo("disabled");
            });
            JsonNode second = requests.get(1);
            assertThat(second.path("messages").toString()).contains("provider-tool-1");
            assertThat(second.path("messages").toString()).contains("echoed: hello");
        } finally {
            server.stop(0);
        }
    }

    private static AgentRunRequest request() {
        return new AgentRunRequest(
                "deepseek-adapter-runtime-it",
                new AgentDefinitionId("deepseek-agent"),
                Optional.empty(),
                "default",
                new AgentSessionId("deepseek-session"),
                Optional.empty(),
                "Call echo and then finish.",
                List.of(),
                RuntimeOverrides.NONE);
    }

    private static void handle(HttpExchange exchange, ObjectMapper json, AtomicInteger calls, List<JsonNode> requests)
            throws IOException {
        requests.add(json.readTree(exchange.getRequestBody()));
        int call = calls.incrementAndGet();
        String body = call == 1
                ? """
                  {"id":"stub-1","model":"deepseek-v4-pro","choices":[{"index":0,"finish_reason":"tool_calls",
                   "message":{"role":"assistant","content":null,"tool_calls":[{"id":"provider-tool-1","type":"function",
                   "function":{"name":"echo","arguments":"{\\"text\\":\\"hello\\"}"}}]}}],
                   "usage":{"prompt_tokens":10,"completion_tokens":3,"total_tokens":13}}
                  """
                : """
                  {"id":"stub-2","model":"deepseek-v4-pro","choices":[{"index":0,"finish_reason":"stop",
                   "message":{"role":"assistant","content":"adapter done"}}],
                   "usage":{"prompt_tokens":15,"completion_tokens":4,"total_tokens":19}}
                  """;
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
