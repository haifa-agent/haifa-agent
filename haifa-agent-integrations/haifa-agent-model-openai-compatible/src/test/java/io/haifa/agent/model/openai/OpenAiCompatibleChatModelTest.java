package io.haifa.agent.model.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelCallId;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinition;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelErrorCategory;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelInvocationException;
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelProviderDefinition;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelStatus;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelToolSpecification;
import io.haifa.agent.model.api.ProviderStatus;
import io.haifa.agent.model.api.ResolvedCredential;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleChatModelTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<Response> response = new AtomicReference<>();
    private HttpServer server;
    private ModelProviderDefinition provider;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", this::handle);
        server.createContext("/models", this::handle);
        server.start();
        provider = provider(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sendsNonStreamingThinkingDisabledDeepSeekRequestAndMapsUsage() throws Exception {
        response.set(
                Response.json(
                        200,
                        """
                {"id":"resp-1","model":"deepseek-v4-pro","system_fingerprint":"fp-1",
                 "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"done"}}],
                 "usage":{"prompt_tokens":12,"completion_tokens":5,"total_tokens":17,
                          "prompt_cache_hit_tokens":3,"prompt_cache_miss_tokens":9,
                          "completion_tokens_details":{"reasoning_tokens":0}}}
                """));

        var actual = model().invoke(request(List.of(ModelMessage.text(ModelMessageRole.USER, "hello")), List.of()));

        assertThat(actual.content()).isEqualTo("done");
        assertThat(actual.finishReason()).isEqualTo(ModelFinishReason.STOP);
        assertThat(actual.usage().inputTokens()).isEqualTo(12);
        assertThat(actual.usage().cacheHitTokens()).isEqualTo(3);
        assertThat(actual.usage().costKnown()).isFalse();
        JsonNode sent = json.readTree(requestBody.get());
        assertThat(sent.path("model").asText()).isEqualTo("deepseek-v4-pro");
        assertThat(sent.path("stream").asBoolean()).isFalse();
        assertThat(sent.path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(sent.path("max_tokens").asInt()).isEqualTo(1024);
        assertThat(authorization.get()).isEqualTo("Bearer test-secret");
    }

    @Test
    void preservesAssistantToolCallsAndToolResultCorrelation() throws Exception {
        response.set(
                Response.json(
                        200,
                        """
                {"id":"resp-2","model":"deepseek-v4-pro",
                 "choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
                   "tool_calls":[{"id":"call-2","type":"function","function":{"name":"weather","arguments":"{\\"city\\":\\"Shanghai\\"}"}}]}}],
                 "usage":{"prompt_tokens":20,"completion_tokens":8,"total_tokens":28}}
                """));
        ModelToolCall previous =
                new ModelToolCall(new ProviderToolCallCorrelationId("call-1"), "weather", Map.of("city", "Beijing"));
        List<ModelMessage> messages = List.of(
                ModelMessage.text(ModelMessageRole.USER, "weather"),
                ModelMessage.assistant("", List.of(previous)),
                ModelMessage.tool(new ProviderToolCallCorrelationId("call-1"), "sunny"));
        ModelToolSpecification tool = new ModelToolSpecification(
                "weather",
                "1.0",
                "Get weather",
                "weather-input",
                "1.0",
                Map.of(
                        "type", "object",
                        "properties", Map.of("city", Map.of("type", "string")),
                        "required", List.of("city")),
                false);

        var actual = model().invoke(request(messages, List.of(tool)));

        assertThat(actual.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.providerCorrelationId().value()).isEqualTo("call-2");
            assertThat(call.name()).isEqualTo("weather");
            assertThat(call.arguments()).containsEntry("city", "Shanghai");
        });
        JsonNode sent = json.readTree(requestBody.get());
        assertThat(sent.path("messages")
                        .get(1)
                        .path("tool_calls")
                        .get(0)
                        .path("id")
                        .asText())
                .isEqualTo("call-1");
        assertThat(sent.path("messages").get(2).path("tool_call_id").asText()).isEqualTo("call-1");
        assertThat(sent.path("tools")
                        .get(0)
                        .path("function")
                        .path("parameters")
                        .path("type")
                        .asText())
                .isEqualTo("object");
    }

    @Test
    void normalizesHttpErrorsWithoutLeakingCredential() {
        Map<Integer, ModelErrorCategory> expected = Map.of(
                400, ModelErrorCategory.INVALID_REQUEST,
                401, ModelErrorCategory.AUTHENTICATION_FAILED,
                403, ModelErrorCategory.PERMISSION_DENIED,
                404, ModelErrorCategory.MODEL_NOT_FOUND,
                408, ModelErrorCategory.TIMEOUT,
                429, ModelErrorCategory.RATE_LIMITED,
                500, ModelErrorCategory.PROVIDER_UNAVAILABLE,
                503, ModelErrorCategory.PROVIDER_UNAVAILABLE);
        expected.forEach((status, category) -> {
            response.set(Response.json(
                    status, "{\"error\":{\"code\":\"failure-test-secret\",\"message\":\"safe test-secret detail\"}}"));
            assertThatThrownBy(() -> model().invoke(simpleRequest()))
                    .isInstanceOf(ModelInvocationException.class)
                    .satisfies(error -> {
                        ModelInvocationException invocation = (ModelInvocationException) error;
                        assertThat(invocation.category()).isEqualTo(category);
                        assertThat(invocation.retryable()).isEqualTo(status == 408 || status == 429 || status >= 500);
                        assertThat(invocation.providerCode()).doesNotContain("test-secret");
                        assertThat(invocation.getMessage())
                                .doesNotContain("test-secret")
                                .doesNotContain("safe detail");
                    });
        });
    }

    @Test
    void rejectsInvalidJsonChoicesArgumentsContentTypeAndOversizedBodies() {
        response.set(Response.json(200, "not-json"));
        assertMalformed();
        response.set(Response.json(200, "{\"id\":\"x\",\"model\":\"deepseek-v4-pro\",\"choices\":[],\"usage\":{}}"));
        assertMalformed();
        response.set(
                Response.json(
                        200,
                        """
                {"id":"x","model":"deepseek-v4-pro","choices":[
                 {"finish_reason":"stop","message":{"content":"one"}},
                 {"finish_reason":"stop","message":{"content":"two"}}],
                 "usage":{"prompt_tokens":1,"completion_tokens":1}}
                """));
        assertMalformed();
        response.set(
                Response.json(
                        200,
                        """
                {"id":"x","model":"deepseek-v4-pro","choices":[{"finish_reason":"tool_calls","message":{"tool_calls":[
                 {"id":"c","type":"function","function":{"name":"weather","arguments":"[]"}}]}}],
                 "usage":{"prompt_tokens":1,"completion_tokens":1}}
                """));
        assertMalformed();
        response.set(new Response(200, "text/plain", "not json"));
        assertMalformed();
        response.set(Response.json(200, "x".repeat(300)));
        assertThatThrownBy(() -> model(128).invoke(simpleRequest()))
                .isInstanceOf(ModelInvocationException.class)
                .satisfies(error -> assertThat(((ModelInvocationException) error).providerCode())
                        .isEqualTo("response_too_large"));
    }

    @Test
    void mapsMultipleToolCallsAndSupportedFinishReasons() {
        response.set(
                Response.json(
                        200,
                        """
                {"id":"multi","model":"deepseek-v4-pro","choices":[{"finish_reason":"tool_calls",
                 "message":{"content":null,"tool_calls":[
                  {"id":"c1","type":"function","function":{"name":"first","arguments":"{\\\"value\\\":1}"}},
                  {"id":"c2","type":"function","function":{"name":"second","arguments":"{\\\"value\\\":2}"}}]}}],
                 "usage":{"prompt_tokens":2,"completion_tokens":3}}
                """));
        var multiple = model().invoke(simpleRequest());
        assertThat(multiple.finishReason()).isEqualTo(ModelFinishReason.TOOL_CALLS);
        assertThat(multiple.toolCalls())
                .extracting(call -> call.providerCorrelationId().value())
                .containsExactly("c1", "c2");

        assertFinishReason("length", ModelFinishReason.LENGTH);
        assertFinishReason("unknown_provider_reason", ModelFinishReason.UNKNOWN);

        response.set(responseWithFinishReason("content_filter"));
        assertThatThrownBy(() -> model().invoke(simpleRequest()))
                .isInstanceOf(ModelInvocationException.class)
                .satisfies(error -> assertThat(((ModelInvocationException) error).category())
                        .isEqualTo(ModelErrorCategory.CONTENT_REJECTED));
        response.set(responseWithFinishReason("insufficient_system_resource"));
        assertThatThrownBy(() -> model().invoke(simpleRequest()))
                .isInstanceOf(ModelInvocationException.class)
                .satisfies(error -> {
                    ModelInvocationException invocation = (ModelInvocationException) error;
                    assertThat(invocation.category()).isEqualTo(ModelErrorCategory.PROVIDER_UNAVAILABLE);
                    assertThat(invocation.retryable()).isTrue();
                });
    }

    @Test
    void normalizesContextLimitUnsupportedOptionsConnectionFailureAndTimeout() throws IOException {
        response.set(Response.json(400, "{\"error\":{\"code\":\"context_length_exceeded\",\"message\":\"too long\"}}"));
        assertThatThrownBy(() -> model().invoke(simpleRequest()))
                .isInstanceOf(ModelInvocationException.class)
                .satisfies(error -> assertThat(((ModelInvocationException) error).category())
                        .isEqualTo(ModelErrorCategory.CONTEXT_TOO_LONG));

        AgentChatRequest unsupported = new AgentChatRequest(
                new ModelCallId("unsupported-options"),
                new AgentRunId("run-1"),
                1,
                1,
                snapshot(),
                List.of(ModelMessage.text(ModelMessageRole.USER, "hello")),
                List.of(),
                16,
                Duration.ofSeconds(1),
                Map.of("temperature", 0));
        assertThatThrownBy(() -> model().invoke(unsupported))
                .isInstanceOf(ModelInvocationException.class)
                .satisfies(error -> assertThat(((ModelInvocationException) error).category())
                        .isEqualTo(ModelErrorCategory.INVALID_REQUEST));

        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        provider = provider(URI.create("http://127.0.0.1:" + closedPort));
        assertThatThrownBy(() -> model().invoke(simpleRequest()))
                .isInstanceOf(ModelInvocationException.class)
                .satisfies(error -> {
                    ModelInvocationException invocation = (ModelInvocationException) error;
                    assertThat(invocation.category()).isEqualTo(ModelErrorCategory.PROVIDER_UNAVAILABLE);
                    assertThat(invocation.retryable()).isTrue();
                });

        provider = provider(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        response.set(Response.delayedJson(
                200,
                """
                {"id":"slow","model":"deepseek-v4-pro","choices":[{"finish_reason":"stop",
                 "message":{"content":"late"}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}
                """,
                250));
        assertThatThrownBy(() -> model().invoke(request(
                        List.of(ModelMessage.text(ModelMessageRole.USER, "hello")), List.of(), Duration.ofMillis(30))))
                .isInstanceOf(ModelInvocationException.class)
                .satisfies(error -> {
                    ModelInvocationException invocation = (ModelInvocationException) error;
                    assertThat(invocation.category()).isEqualTo(ModelErrorCategory.TIMEOUT);
                    assertThat(invocation.retryable()).isTrue();
                });
    }

    @Test
    void rejectsThinkingEnabledProviderAndResolvesOnlyEnvironmentReferences() {
        ModelProviderDefinition enabled = new ModelProviderDefinition(
                provider.id(),
                provider.version(),
                provider.displayName(),
                provider.adapterType(),
                provider.endpoint(),
                provider.credentialRef(),
                provider.status(),
                provider.models(),
                Map.of("thinking", "enabled"),
                Map.of());
        assertThatThrownBy(() -> new OpenAiCompatibleChatModel(
                        enabled,
                        HttpClient.newHttpClient(),
                        json,
                        ignored -> new ResolvedCredential("secret"),
                        true,
                        1024))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("thinking=disabled");

        EnvironmentCredentialResolver resolver =
                new EnvironmentCredentialResolver(name -> name.equals("DEEPSEEK_API_KEY") ? "resolved-secret" : null);
        assertThat(resolver.resolve(new CredentialRef("env://DEEPSEEK_API_KEY")).value())
                .isEqualTo("resolved-secret");
        assertThatThrownBy(() -> resolver.resolve(new CredentialRef("file://secret")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scheme");
    }

    @Test
    void observesProviderHealthWithoutChangingConfigurationState() {
        response.set(Response.json(200, "{\"object\":\"list\",\"data\":[]}"));
        OpenAiCompatibleHealthProbe probe = new OpenAiCompatibleHealthProbe(
                provider,
                HttpClient.newHttpClient(),
                ignored -> new ResolvedCredential("test-secret"),
                Duration.ofSeconds(2),
                Clock.fixed(Instant.parse("2026-07-21T00:00:00Z"), ZoneOffset.UTC),
                true);

        var healthy = probe.check();
        response.set(Response.json(429, "{\"error\":{\"code\":\"rate_limit\"}}"));
        var limited = probe.check();

        assertThat(healthy.status()).isEqualTo(io.haifa.agent.model.api.ProviderHealthStatus.HEALTHY);
        assertThat(limited.status()).isEqualTo(io.haifa.agent.model.api.ProviderHealthStatus.RATE_LIMITED);
        assertThat(provider.status()).isEqualTo(ProviderStatus.ACTIVE);
    }

    private void assertMalformed() {
        assertThatThrownBy(() -> model().invoke(simpleRequest()))
                .isInstanceOf(ModelInvocationException.class)
                .satisfies(error -> assertThat(((ModelInvocationException) error).category())
                        .isEqualTo(ModelErrorCategory.MALFORMED_RESPONSE));
    }

    private OpenAiCompatibleChatModel model() {
        return model(1024 * 1024);
    }

    private OpenAiCompatibleChatModel model(int maxResponseBytes) {
        return new OpenAiCompatibleChatModel(
                provider,
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                json,
                ignored -> new ResolvedCredential("test-secret"),
                true,
                maxResponseBytes);
    }

    private AgentChatRequest simpleRequest() {
        return request(List.of(ModelMessage.text(ModelMessageRole.USER, "hello")), List.of());
    }

    private AgentChatRequest request(List<ModelMessage> messages, List<ModelToolSpecification> tools) {
        return request(messages, tools, Duration.ofSeconds(5));
    }

    private AgentChatRequest request(
            List<ModelMessage> messages, List<ModelToolSpecification> tools, Duration timeout) {
        return new AgentChatRequest(
                new ModelCallId("call-1"),
                new AgentRunId("run-1"),
                1,
                1,
                snapshot(),
                messages,
                tools,
                1024,
                timeout,
                Map.of());
    }

    private void assertFinishReason(String providerReason, ModelFinishReason expected) {
        response.set(responseWithFinishReason(providerReason));
        assertThat(model().invoke(simpleRequest()).finishReason()).isEqualTo(expected);
    }

    private Response responseWithFinishReason(String finishReason) {
        return Response.json(
                200,
                """
                {"id":"finish","model":"deepseek-v4-pro","choices":[{"finish_reason":"%s",
                 "message":{"content":"value"}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}
                """
                        .formatted(finishReason));
    }

    private ResolvedModelSnapshot snapshot() {
        return ResolvedModelSnapshot.create(
                provider.id(),
                provider.version(),
                new ModelDefinitionId("deepseek-v4-pro"),
                "model-v1",
                "deepseek-v4-pro",
                "openai-compatible",
                "1.0.0",
                provider.endpoint(),
                provider.credentialRef(),
                EnumSet.allOf(ModelCapability.class),
                1_048_576,
                393_216,
                provider.options(),
                Map.of("thinking", "disabled"));
    }

    private ModelProviderDefinition provider(URI endpoint) {
        ModelProviderId providerId = new ModelProviderId("deepseek");
        ModelDefinition model = new ModelDefinition(
                new ModelDefinitionId("deepseek-v4-pro"),
                "model-v1",
                providerId,
                "deepseek-v4-pro",
                "DeepSeek V4 Pro",
                ModelStatus.ACTIVE,
                EnumSet.allOf(ModelCapability.class),
                1_048_576,
                393_216,
                Map.of("thinking", "disabled"),
                Map.of());
        return new ModelProviderDefinition(
                providerId,
                "provider-v1",
                "DeepSeek",
                "openai-compatible",
                endpoint,
                new CredentialRef("env://DEEPSEEK_API_KEY"),
                ProviderStatus.ACTIVE,
                List.of(model),
                Map.of("thinking", "disabled"),
                Map.of());
    }

    private void handle(HttpExchange exchange) throws IOException {
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        Response selected = response.get();
        if (selected.delayMillis() > 0) {
            try {
                Thread.sleep(selected.delayMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                exchange.close();
                return;
            }
        }
        byte[] bytes = selected.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", selected.contentType());
        exchange.sendResponseHeaders(selected.status(), bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record Response(int status, String contentType, String body, long delayMillis) {
        Response(int status, String contentType, String body) {
            this(status, contentType, body, 0);
        }

        static Response json(int status, String body) {
            return new Response(status, "application/json; charset=utf-8", body);
        }

        static Response delayedJson(int status, String body, long delayMillis) {
            return new Response(status, "application/json; charset=utf-8", body, delayMillis);
        }
    }
}
