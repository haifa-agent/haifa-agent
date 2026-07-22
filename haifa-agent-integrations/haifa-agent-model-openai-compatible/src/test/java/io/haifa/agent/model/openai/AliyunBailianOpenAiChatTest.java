package io.haifa.agent.model.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelCallId;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelErrorCategory;
import io.haifa.agent.model.api.ModelInvocationException;
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelProviderDefinition;
import io.haifa.agent.model.api.ModelStreamControl;
import io.haifa.agent.model.api.ModelStreamEvent;
import io.haifa.agent.model.api.ResolvedCredential;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AliyunBailianOpenAiChatTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<Response> response = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();
    private HttpServer server;
    private ModelProviderDefinition provider;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", this::handle);
        server.start();
        provider = provider(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Map.of(
                        "thinking_profile", "hybrid",
                        "thinking_enabled", true,
                        "thinking_budget", 2048,
                        "preserve_thinking", false,
                        "supports_tool_stream", true,
                        "tool_stream", false));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sendsBailianThinkingProfileWithoutDeepSeekFieldsAndMapsCachedUsage() throws Exception {
        response.set(
                Response.json(
                        200,
                        """
                {"id":"bailian-1","model":"configured-qwen","choices":[{"finish_reason":"stop",
                 "message":{"content":"answer","reasoning_content":"private thought"}}],
                 "usage":{"prompt_tokens":10,"completion_tokens":4,
                          "prompt_tokens_details":{"cached_tokens":6},
                          "completion_tokens_details":{"reasoning_tokens":2}}}
                """));

        var result = model(provider).invoke(request(snapshot(provider), Map.of()));

        JsonNode sent = json.readTree(body.get());
        assertThat(sent.path("enable_thinking").asBoolean()).isTrue();
        assertThat(sent.path("thinking_budget").asInt()).isEqualTo(2048);
        assertThat(sent.has("thinking")).isFalse();
        assertThat(sent.has("tool_stream")).isFalse();
        assertThat(authorization.get()).isEqualTo("Bearer test-bailian-secret");
        assertThat(result.usage().cacheHitTokens()).isEqualTo(6);
        assertThat(result.usage().reasoningTokens()).isEqualTo(2);
        assertThat(result.toString()).doesNotContain("private thought");
    }

    @Test
    void acceptsFinalUsageOnlyChunkAndAggregatesToolArguments() {
        response.set(
                Response.sse(
                        """
                data: {"id":"bailian-stream","model":"configured-qwen","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call-a","type":"function","function":{"name":"weather","arguments":"{\\"city\\":"}}]},"finish_reason":null}]}

                data: {"id":"bailian-stream","model":"configured-qwen","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\"Hangzhou\\"}"}}]},"finish_reason":"tool_calls"}]}

                data: {"id":"bailian-stream","model":"configured-qwen","choices":[],"usage":{"prompt_tokens":7,"completion_tokens":2,"prompt_tokens_details":{"cached_tokens":3}}}

                data: [DONE]

                """));
        List<ModelStreamEvent> events = new ArrayList<>();

        var result = model(provider).invokeStreaming(request(snapshot(provider), Map.of()), event -> {
            events.add(event);
            return ModelStreamControl.CONTINUE;
        });

        assertThat(result.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo("weather");
            assertThat(call.arguments()).containsEntry("city", "Hangzhou");
        });
        assertThat(result.usage().cacheHitTokens()).isEqualTo(3);
        assertThat(events)
                .filteredOn(ModelStreamEvent.ToolCallDelta.class::isInstance)
                .hasSize(2);
    }

    @Test
    void rejectsUnsupportedProfilesAndForcedToolBeforeHttp() {
        ModelProviderDefinition unsupported = provider(
                provider.endpoint(),
                Map.of(
                        "thinking_profile", "none",
                        "thinking_enabled", true,
                        "supports_tool_stream", false,
                        "tool_stream", false));
        assertThatThrownBy(() -> model(unsupported))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not support thinking");
        assertThat(requests).hasValue(0);

        response.set(Response.json(200, "{}"));
        assertThatThrownBy(() -> model(provider)
                        .invoke(request(
                                snapshot(provider),
                                Map.of(
                                        "tool_choice",
                                        Map.of("type", "function", "function", Map.of("name", "weather"))))))
                .isInstanceOf(ModelInvocationException.class)
                .satisfies(error -> assertThat(((ModelInvocationException) error).category())
                        .isEqualTo(ModelErrorCategory.INVALID_REQUEST));
        assertThat(requests).hasValue(0);
    }

    @Test
    void validatesRegionWorkspaceAndDialectVersionAtBootstrap() {
        ModelProviderDefinition publicAsWorkspace = provider(
                URI.create("https://dashscope.aliyuncs.com/compatible-mode/v1"),
                Map.of("thinking_profile", "none", "thinking_enabled", false));
        publicAsWorkspace = new ModelProviderDefinition(
                publicAsWorkspace.id(),
                publicAsWorkspace.version(),
                publicAsWorkspace.displayName(),
                publicAsWorkspace.adapterType(),
                publicAsWorkspace.endpoint(),
                publicAsWorkspace.credentialRef(),
                publicAsWorkspace.status(),
                publicAsWorkspace.models(),
                Map.of(
                        "dialect_id", "aliyun-bailian-openai-chat",
                        "dialect_version", "1.0",
                        "region", "cn-beijing",
                        "workspace_scoped", true),
                publicAsWorkspace.metadata());
        ModelProviderDefinition invalidWorkspace = publicAsWorkspace;
        assertThatThrownBy(() -> new OpenAiCompatibleChatModel(
                        invalidWorkspace,
                        HttpClient.newHttpClient(),
                        json,
                        ignored -> new ResolvedCredential("secret")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dedicated domain");

        ModelProviderDefinition validPublic = provider(
                URI.create("https://dashscope.aliyuncs.com/compatible-mode/v1"),
                Map.of("thinking_profile", "none", "thinking_enabled", false));
        new OpenAiCompatibleChatModel(
                validPublic, HttpClient.newHttpClient(), json, ignored -> new ResolvedCredential("secret"));
    }

    @Test
    void normalizesBailianAuthenticationAndRateLimitErrorsWithoutLeakingBody() {
        response.set(Response.json(
                401, "{\"error\":{\"code\":\"InvalidApiKey\",\"message\":\"contains test-bailian-secret\"}}"));
        assertFailure(ModelErrorCategory.AUTHENTICATION_FAILED, "test-bailian-secret");

        response.set(Response.json(429, "{\"error\":{\"code\":\"Throttling.RateQuota\"}}"));
        assertFailure(ModelErrorCategory.RATE_LIMITED, "Throttling.RateQuota");
    }

    private void assertFailure(ModelErrorCategory category, String forbidden) {
        assertThatThrownBy(() -> model(provider).invoke(request(snapshot(provider), Map.of())))
                .isInstanceOf(ModelInvocationException.class)
                .satisfies(error -> {
                    ModelInvocationException failure = (ModelInvocationException) error;
                    assertThat(failure.category()).isEqualTo(category);
                    assertThat(failure.getMessage()).doesNotContain(forbidden);
                });
    }

    private OpenAiCompatibleChatModel model(ModelProviderDefinition definition) {
        return new OpenAiCompatibleChatModel(
                definition,
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                json,
                ignored -> new ResolvedCredential("test-bailian-secret"),
                true,
                1024 * 1024);
    }

    private ModelProviderDefinition provider(URI endpoint, Map<String, Object> modelOptions) {
        var configuration = new AliyunBailianProviderFactory.ProviderConfiguration(
                "provider-v1", endpoint, "cn-beijing", false, new CredentialRef("env://DASHSCOPE_API_KEY"));
        var profile = new AliyunBailianProviderFactory.ModelProfile(
                new ModelDefinitionId("configured-qwen"),
                "profile-v1",
                "configured-qwen",
                "Configured Qwen",
                EnumSet.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING, ModelCapability.REASONING),
                131_072,
                16_384,
                modelOptions);
        return AliyunBailianProviderFactory.provider(configuration, List.of(profile));
    }

    private ResolvedModelSnapshot snapshot(ModelProviderDefinition definition) {
        var profile = definition.models().getFirst();
        return ResolvedModelSnapshot.create(
                definition.id(),
                definition.version(),
                profile.id(),
                profile.version(),
                profile.providerModelId(),
                definition.adapterType(),
                "1.0.0",
                definition.endpoint(),
                definition.credentialRef(),
                profile.capabilities(),
                profile.contextWindow(),
                profile.maxOutputTokens(),
                definition.options(),
                profile.options());
    }

    private AgentChatRequest request(ResolvedModelSnapshot snapshot, Map<String, Object> options) {
        return new AgentChatRequest(
                new ModelCallId("bailian-call"),
                new AgentRunId("bailian-run"),
                1,
                1,
                snapshot,
                List.of(ModelMessage.text(ModelMessageRole.USER, "hello")),
                List.of(),
                1024,
                Duration.ofSeconds(5),
                options);
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        Response selected = response.get();
        byte[] bytes = selected.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", selected.contentType());
        exchange.sendResponseHeaders(selected.status(), bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record Response(int status, String contentType, String body) {
        static Response json(int status, String body) {
            return new Response(status, "application/json; charset=utf-8", body);
        }

        static Response sse(String body) {
            return new Response(200, "text/event-stream; charset=utf-8", body);
        }
    }
}
