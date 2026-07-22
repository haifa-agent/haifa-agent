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
import io.haifa.agent.model.api.ModelReferenceKind;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VolcengineArkOpenAiChatTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<Response> response = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private HttpServer server;
    private ModelProviderDefinition provider;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v3/chat/completions", this::handle);
        server.start();
        provider = provider(
                ModelReferenceKind.ENDPOINT_ID,
                Map.of(
                        "thinking_profile", "hybrid",
                        "thinking_enabled", true,
                        "reasoning_effort", "medium",
                        "requires_reasoning_continuation", false,
                        "token_limit_parameter", "max_completion_tokens",
                        "service_tier", "default"));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void appendsChatPathOnceAndKeepsFrozenEndpointReferenceSeparateFromActualModel() throws Exception {
        response.set(
                Response.json(
                        200,
                        """
                {"id":"ark-1","object":"chat.completion","model":"doubao-actual-version",
                 "choices":[{"finish_reason":"stop","message":{"content":"answer","reasoning_content":"private"}}],
                 "usage":{"prompt_tokens":8,"completion_tokens":3,"prompt_tokens_details":{"cached_tokens":2},
                          "completion_tokens_details":{"reasoning_tokens":1}}}
                """));

        var result = model(provider).invoke(request(snapshot(provider)));

        assertThat(requestPath.get()).isEqualTo("/api/v3/chat/completions");
        assertThat(result.actualModelId()).isEqualTo("doubao-actual-version");
        assertThat(snapshot(provider).providerModelId()).isEqualTo("configured-ark-reference");
        assertThat(snapshot(provider).invocationOptions()).containsEntry("model_reference_kind", "ENDPOINT_ID");
        assertThat(result.usage().cacheHitTokens()).isEqualTo(2);
        assertThat(result.toString()).doesNotContain("private");
        JsonNode sent = json.readTree(requestBody.get());
        assertThat(sent.path("model").asText()).isEqualTo("configured-ark-reference");
        assertThat(sent.path("thinking").path("type").asText()).isEqualTo("enabled");
        assertThat(sent.path("reasoning_effort").asText()).isEqualTo("medium");
        assertThat(sent.path("max_completion_tokens").asInt()).isEqualTo(512);
        assertThat(sent.has("max_tokens")).isFalse();
        assertThat(sent.path("service_tier").asText()).isEqualTo("default");
        assertThat(sent.has("enable_thinking")).isFalse();
    }

    @Test
    void supportsModelIdAndEndpointIdWithoutPrefixInference() {
        assertThat(snapshot(provider(
                                ModelReferenceKind.MODEL_ID,
                                Map.of("thinking_profile", "none", "thinking_enabled", false)))
                        .invocationOptions())
                .containsEntry("model_reference_kind", "MODEL_ID");
        assertThat(snapshot(provider).invocationOptions()).containsEntry("model_reference_kind", "ENDPOINT_ID");
    }

    @Test
    void streamsContentAndAcceptsFinalEmptyChoicesUsage() {
        response.set(
                Response.sse(
                        """
                data: {"id":"ark-stream","object":"chat.completion.chunk","model":"actual","choices":[{"index":0,"delta":{"content":"hel"},"finish_reason":null}]}

                data: {"id":"ark-stream","object":"chat.completion.chunk","model":"actual","choices":[{"index":0,"delta":{"content":"lo"},"finish_reason":"stop"}]}

                data: {"id":"ark-stream","object":"chat.completion.chunk","model":"actual","choices":[],"usage":{"prompt_tokens":2,"completion_tokens":2}}

                data: [DONE]

                """));
        List<ModelStreamEvent> events = new ArrayList<>();

        var result = model(provider).invokeStreaming(request(snapshot(provider)), event -> {
            events.add(event);
            return ModelStreamControl.CONTINUE;
        });

        assertThat(result.content()).isEqualTo("hello");
        assertThat(events)
                .filteredOn(ModelStreamEvent.ContentDelta.class::isInstance)
                .hasSize(2);
        assertThat(events)
                .filteredOn(ModelStreamEvent.UsageReported.class::isInstance)
                .hasSize(1);
    }

    @Test
    void aggregatesToolFragmentsAndRejectsInvalidArguments() {
        response.set(
                Response.sse(
                        """
                data: {"id":"ark-tool","object":"chat.completion.chunk","model":"actual","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"tool-1","type":"function","function":{"name":"weather","arguments":"{\\"city\\":"}}]},"finish_reason":null}]}

                data: {"id":"ark-tool","object":"chat.completion.chunk","model":"actual","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\"Beijing\\"}"}}]},"finish_reason":"tool_calls"}]}

                data: {"id":"ark-tool","object":"chat.completion.chunk","model":"actual","choices":[],"usage":{"prompt_tokens":3,"completion_tokens":2}}

                data: [DONE]

                """));
        var result =
                model(provider).invokeStreaming(request(snapshot(provider)), ignored -> ModelStreamControl.CONTINUE);
        assertThat(result.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo("weather");
            assertThat(call.arguments()).containsEntry("city", "Beijing");
        });

        response.set(
                Response.json(
                        200,
                        """
                {"id":"ark-bad-tool","object":"chat.completion","model":"actual",
                 "choices":[{"finish_reason":"tool_calls","message":{"content":"","tool_calls":[
                 {"id":"tool-bad","type":"function","function":{"name":"weather","arguments":"[]"}}]}}],
                 "usage":{"prompt_tokens":2,"completion_tokens":1}}
                """));
        assertFailure(ModelErrorCategory.MALFORMED_RESPONSE);
    }

    @Test
    void rejectsWrongEnvelopeAndUnsupportedThinkingBeforeNetwork() {
        response.set(
                Response.json(
                        200,
                        """
                {"id":"bad","object":"response","model":"actual","choices":[{"finish_reason":"stop",
                 "message":{"content":"answer"}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}
                """));
        assertFailure(ModelErrorCategory.MALFORMED_RESPONSE);

        ModelProviderDefinition unsupported =
                provider(ModelReferenceKind.MODEL_ID, Map.of("thinking_profile", "none", "thinking_enabled", true));
        assertThatThrownBy(() -> model(unsupported))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not support thinking");
    }

    @Test
    void classifiesContentSafetyEndpointStoppedRateLimitAndStreamErrors() {
        response.set(
                Response.json(400, "{\"error\":{\"code\":\"ContentSafetyRisk\",\"message\":\"content filtered\"}}"));
        assertFailure(ModelErrorCategory.CONTENT_REJECTED);
        response.set(Response.json(404, "{\"error\":{\"code\":\"EndpointStopped\",\"message\":\"endpoint stopped\"}}"));
        assertFailure(ModelErrorCategory.MODEL_NOT_FOUND);
        response.set(Response.json(429, "{\"error\":{\"code\":\"RateLimitExceeded\"}}"));
        assertThatThrownBy(() -> model(provider).invoke(request(snapshot(provider))))
                .isInstanceOf(ModelInvocationException.class)
                .satisfies(error -> {
                    var failure = (ModelInvocationException) error;
                    assertThat(failure.category()).isEqualTo(ModelErrorCategory.RATE_LIMITED);
                    assertThat(failure.retryable()).isTrue();
                });
        response.set(Response.sse("""
                data: {"error":{"code":"EndpointStopped"}}

                """));
        assertFailureStreaming(ModelErrorCategory.MODEL_NOT_FOUND);
    }

    @Test
    void validatesGovernedHostRegionAndReferenceKind() {
        var definition = VolcengineArkProviderFactory.provider(
                VolcengineArkProviderFactory.ProviderConfiguration.beijingExample(),
                List.of(new VolcengineArkProviderFactory.ModelProfile(
                        new ModelDefinitionId("ark-production"),
                        "profile-v1",
                        "model-or-endpoint",
                        "Ark Production",
                        ModelReferenceKind.MODEL_ID,
                        EnumSet.of(ModelCapability.TEXT_CHAT),
                        32_768,
                        4096,
                        Map.of("thinking_profile", "none", "thinking_enabled", false))));
        new OpenAiCompatibleChatModel(
                definition, HttpClient.newHttpClient(), json, ignored -> new ResolvedCredential("secret"));

        var invalidConfiguration = new VolcengineArkProviderFactory.ProviderConfiguration(
                "v1",
                URI.create("https://ark.cn-beijing.volces.com/api/v3"),
                "cn-beijing",
                "ark.cn-shanghai.volces.com",
                new CredentialRef("env://ARK_API_KEY"));
        var invalid = VolcengineArkProviderFactory.provider(
                invalidConfiguration,
                definition.models().stream()
                        .map(model -> new VolcengineArkProviderFactory.ModelProfile(
                                model.id(),
                                model.version(),
                                model.providerModelId(),
                                model.displayName(),
                                ModelReferenceKind.MODEL_ID,
                                model.capabilities(),
                                model.contextWindow(),
                                model.maxOutputTokens(),
                                model.options()))
                        .toList());
        assertThatThrownBy(() -> new OpenAiCompatibleChatModel(
                        invalid, HttpClient.newHttpClient(), json, ignored -> new ResolvedCredential("secret")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("do not match");
    }

    private void assertFailure(ModelErrorCategory category) {
        assertThatThrownBy(() -> model(provider).invoke(request(snapshot(provider))))
                .isInstanceOf(ModelInvocationException.class)
                .satisfies(error -> assertThat(((ModelInvocationException) error).category())
                        .isEqualTo(category));
    }

    private void assertFailureStreaming(ModelErrorCategory category) {
        assertThatThrownBy(() -> model(provider)
                        .invokeStreaming(request(snapshot(provider)), ignored -> ModelStreamControl.CONTINUE))
                .isInstanceOf(ModelInvocationException.class)
                .satisfies(error -> assertThat(((ModelInvocationException) error).category())
                        .isEqualTo(category));
    }

    private OpenAiCompatibleChatModel model(ModelProviderDefinition definition) {
        return new OpenAiCompatibleChatModel(
                definition,
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                json,
                ignored -> new ResolvedCredential("ark-test-secret"),
                true,
                1024 * 1024);
    }

    private ModelProviderDefinition provider(ModelReferenceKind kind, Map<String, Object> options) {
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v3");
        var configuration = new VolcengineArkProviderFactory.ProviderConfiguration(
                "provider-v1",
                endpoint,
                "cn-beijing",
                "ark.cn-beijing.volces.com",
                new CredentialRef("env://ARK_API_KEY"));
        var profile = new VolcengineArkProviderFactory.ModelProfile(
                new ModelDefinitionId("ark-configured"),
                "profile-v1",
                "configured-ark-reference",
                "Configured Ark",
                kind,
                EnumSet.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING, ModelCapability.REASONING),
                131_072,
                16_384,
                options);
        return VolcengineArkProviderFactory.provider(configuration, List.of(profile));
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

    private AgentChatRequest request(ResolvedModelSnapshot snapshot) {
        return new AgentChatRequest(
                new ModelCallId("ark-call"),
                new AgentRunId("ark-run"),
                1,
                1,
                snapshot,
                List.of(ModelMessage.text(ModelMessageRole.USER, "hello")),
                List.of(),
                512,
                Duration.ofSeconds(5),
                Map.of());
    }

    private void handle(HttpExchange exchange) throws IOException {
        requestPath.set(exchange.getRequestURI().getPath());
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
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
