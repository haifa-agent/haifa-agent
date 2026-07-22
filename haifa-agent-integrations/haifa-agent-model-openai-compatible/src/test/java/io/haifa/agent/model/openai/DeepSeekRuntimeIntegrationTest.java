package io.haifa.agent.model.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.run.AgentRunType;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.model.api.ModelProviderDefinition;
import io.haifa.agent.model.api.ResolvedCredential;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.RuntimeOverrides;
import io.haifa.agent.runtime.core.RuntimeCoreBuilder;
import io.haifa.agent.runtime.core.bootstrap.ResolvedProfile;
import io.haifa.agent.runtime.core.execution.ManualExecutionScheduler;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import io.haifa.agent.tool.api.SemanticVersion;
import io.haifa.agent.tool.api.ToolAlias;
import io.haifa.agent.tool.api.ToolApprovalRequirement;
import io.haifa.agent.tool.api.ToolDefinition;
import io.haifa.agent.tool.api.ToolExecutionMode;
import io.haifa.agent.tool.api.ToolIdempotency;
import io.haifa.agent.tool.api.ToolName;
import io.haifa.agent.tool.api.ToolProvider;
import io.haifa.agent.tool.api.ToolProviderId;
import io.haifa.agent.tool.api.ToolResourceRequirements;
import io.haifa.agent.tool.api.ToolRisk;
import io.haifa.agent.tool.api.ToolSchema;
import io.haifa.agent.tool.api.ToolSideEffect;
import io.haifa.agent.tool.core.DefaultToolInvoker;
import io.haifa.agent.tool.core.JsonSchema202012Validator;
import io.haifa.agent.tool.core.ToolCatalogBuilder;
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
                    defaults.version(),
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
            var modelDefinition = provider.models().getFirst();
            ResolvedModelSnapshot frozenModel = ResolvedModelSnapshot.create(
                    provider.id(),
                    provider.version(),
                    modelDefinition.id(),
                    modelDefinition.version(),
                    modelDefinition.providerModelId(),
                    provider.adapterType(),
                    "1.0.0",
                    endpoint,
                    provider.credentialRef(),
                    modelDefinition.capabilities(),
                    modelDefinition.contextWindow(),
                    modelDefinition.maxOutputTokens(),
                    provider.options(),
                    modelDefinition.options());
            ToolProviderId toolProviderId = new ToolProviderId("integration-test");
            ToolProvider toolProvider = new ToolProvider() {
                @Override
                public ToolProviderId id() {
                    return toolProviderId;
                }

                @Override
                public ToolResult invoke(io.haifa.agent.tool.api.ToolInvocationRequest request) {
                    return new ToolResult(
                            true,
                            "echoed: " + request.arguments().values().get("text"),
                            request.arguments().values(),
                            List.of(),
                            List.of(),
                            false);
                }
            };
            Map<String, Object> inputSchema = Map.of(
                    "$schema",
                    ToolSchema.DRAFT_2020_12,
                    "type",
                    "object",
                    "properties",
                    Map.of("text", Map.of("type", "string")),
                    "required",
                    List.of("text"),
                    "additionalProperties",
                    false);
            Map<String, Object> outputSchema =
                    Map.of("$schema", ToolSchema.DRAFT_2020_12, "type", "object", "additionalProperties", true);
            ToolDefinition echo = new ToolDefinition(
                    new ToolName("echo"),
                    new SemanticVersion("1.0.0"),
                    toolProviderId,
                    "Echo",
                    "Echo a text value",
                    new ToolSchema("echo.input", "1.0", inputSchema),
                    new ToolSchema("echo.output", "1.0", outputSchema),
                    ToolExecutionMode.IN_PROCESS,
                    true,
                    java.time.Duration.ofSeconds(10),
                    "single",
                    ToolIdempotency.IDEMPOTENT,
                    ToolRisk.LOW,
                    java.util.Set.of(ToolSideEffect.FILE_READ),
                    ToolResourceRequirements.none(),
                    List.of(),
                    ToolApprovalRequirement.NEVER,
                    "integration-test",
                    false,
                    java.util.Set.of("test"));
            var toolCatalog = new ToolCatalogBuilder()
                    .register(new ToolAlias("echo"), echo, "integration-test", toolProvider)
                    .freeze();
            var runtime = new RuntimeCoreBuilder()
                    .registerChatModel("openai-compatible", "1.0.0", adapter)
                    .profiles((id, overrides) -> new ResolvedProfile(
                            id,
                            "1.0.0",
                            AgentRunType.CHAT,
                            new AgentRunBudget(1_000_000, 1_000_000, 1_000_000, 32, 64, 8, "USD", 1_000_000),
                            new AgentRunLimits(50, 4, 1, 300_000, 60_000),
                            frozenModel))
                    .toolPlatform(toolCatalog, new DefaultToolInvoker(toolCatalog), new JsonSchema202012Validator())
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
            JsonNode toolMessage = java.util.stream.StreamSupport.stream(
                            second.path("messages").spliterator(), false)
                    .filter(message -> message.path("role").asText().equals("tool"))
                    .findFirst()
                    .orElseThrow();
            JsonNode toolResult = json.readTree(toolMessage.path("content").asText());
            assertThat(toolResult.path("structuredData").path("text").asText()).isEqualTo("hello");
            assertThat(toolResult.path("truncated").asBoolean()).isFalse();
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
