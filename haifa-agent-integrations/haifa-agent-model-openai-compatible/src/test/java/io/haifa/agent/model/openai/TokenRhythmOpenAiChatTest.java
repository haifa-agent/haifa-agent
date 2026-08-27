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
import io.haifa.agent.model.api.ModelApiBindingDefinition;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCallId;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinition;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelErrorCategory;
import io.haifa.agent.model.api.ModelInvocationException;
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelProviderDefinition;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelStatus;
import io.haifa.agent.model.api.ModelStreamControl;
import io.haifa.agent.model.api.ModelStreamEvent;
import io.haifa.agent.model.api.ModelToolSpecification;
import io.haifa.agent.model.api.ProviderStatus;
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

class TokenRhythmOpenAiChatTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<String> responseBody = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();
    private HttpServer server;
    private ModelProviderDefinition provider;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", this::handle);
        server.start();
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        provider = provider(
                endpoint, OpenAiCompatibleDialects.configuredOptions(OpenAiCompatibleDialects.TOKENRHYTHM, endpoint));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void mergesCumulativeUsageAndPublishesOnlyTheFinalSnapshot() throws Exception {
        responseBody.set(
                """
                data: {"id":"tr-stream","model":"deepseek-v4-flash","choices":[{"index":0,"delta":{"reasoning_content":"private thought","content":"hel"},"finish_reason":null}],"usage":{"prompt_tokens":0,"completion_tokens":0}}

                data: {"id":"tr-stream","model":"deepseek-v4-flash","choices":[],"usage":{"prompt_tokens":0,"completion_tokens":0}}

                data: {"id":"tr-stream","model":"deepseek-v4-flash","choices":[{"index":0,"delta":{"content":"lo"},"finish_reason":"stop"}],"usage":{"prompt_tokens":0,"completion_tokens":2}}

                data: {"id":"tr-stream","model":"deepseek-v4-flash","choices":[],"usage":{"prompt_tokens":5,"completion_tokens":5}}

                data: [DONE]

                """);
        List<ModelStreamEvent> events = new ArrayList<>();

        var result = model(provider).invokeStreaming(request(snapshot(provider), List.of()), event -> {
            events.add(event);
            return ModelStreamControl.CONTINUE;
        });

        assertThat(result.content()).isEqualTo("hello");
        assertThat(result.usage().inputTokens()).isEqualTo(5);
        assertThat(result.usage().outputTokens()).isEqualTo(5);
        assertThat(result.metadata()).containsEntry("reasoningCharacters", 15);
        assertThat(result.toString()).doesNotContain("private thought");
        assertThat(events)
                .filteredOn(ModelStreamEvent.UsageReported.class::isInstance)
                .hasSize(1);
        assertThat(events.stream().map(Object::toString)).noneMatch(value -> value.contains("private thought"));
        JsonNode sent = json.readTree(requestBody.get());
        assertThat(sent.path("stream").asBoolean()).isTrue();
        assertThat(sent.path("stream_options").path("include_usage").asBoolean())
                .isTrue();
    }

    @Test
    void assemblesToolArgumentsWhileKeepingFinalUsageSingle() {
        responseBody.set(
                """
                data: {"id":"tr-tool","model":"deepseek-v4-flash","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call-weather","type":"function","function":{"name":"weather","arguments":"{\\"city\\":"}}]},"finish_reason":null}],"usage":{"prompt_tokens":0,"completion_tokens":0}}

                data: {"id":"tr-tool","model":"deepseek-v4-flash","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\"Paris\\"}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":4,"completion_tokens":1}}

                data: [DONE]

                """);
        List<ModelStreamEvent> events = new ArrayList<>();
        var tool = new ModelToolSpecification(
                "weather", "1", "Weather", "weather-input", "1", Map.of("type", "object"), false);

        var result = model(provider).invokeStreaming(request(snapshot(provider), List.of(tool)), event -> {
            events.add(event);
            return ModelStreamControl.CONTINUE;
        });

        assertThat(result.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo("weather");
            assertThat(call.arguments()).containsEntry("city", "Paris");
        });
        assertThat(events)
                .filteredOn(ModelStreamEvent.ToolCallDelta.class::isInstance)
                .hasSize(2);
        assertThat(events)
                .filteredOn(ModelStreamEvent.UsageReported.class::isInstance)
                .hasSize(1);
    }

    @Test
    void rejectsDecreasingUsageAndMissingUsage() {
        responseBody.set(
                """
                data: {"id":"tr-bad","model":"deepseek-v4-flash","choices":[{"index":0,"delta":{"content":"x"},"finish_reason":null}],"usage":{"prompt_tokens":4,"completion_tokens":2}}

                data: {"id":"tr-bad","model":"deepseek-v4-flash","choices":[{"index":0,"delta":{"content":"y"},"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":1}}

                data: [DONE]

                """);
        assertProviderCode("non_monotonic_stream_usage");

        responseBody.set(
                """
                data: {"id":"tr-missing","model":"deepseek-v4-flash","choices":[{"index":0,"delta":{"content":"answer"},"finish_reason":"stop"}]}

                data: [DONE]

                """);
        assertProviderCode("missing_usage");
    }

    @Test
    void rejectsDecreaseInEveryCumulativeTokenField() {
        List<String> decreasingUsages = List.of(
                "{\"prompt_tokens\":4,\"completion_tokens\":6,\"prompt_cache_hit_tokens\":6,\"prompt_cache_miss_tokens\":6,\"completion_tokens_details\":{\"reasoning_tokens\":6}}",
                "{\"prompt_tokens\":6,\"completion_tokens\":4,\"prompt_cache_hit_tokens\":6,\"prompt_cache_miss_tokens\":6,\"completion_tokens_details\":{\"reasoning_tokens\":6}}",
                "{\"prompt_tokens\":6,\"completion_tokens\":6,\"prompt_cache_hit_tokens\":4,\"prompt_cache_miss_tokens\":6,\"completion_tokens_details\":{\"reasoning_tokens\":6}}",
                "{\"prompt_tokens\":6,\"completion_tokens\":6,\"prompt_cache_hit_tokens\":6,\"prompt_cache_miss_tokens\":4,\"completion_tokens_details\":{\"reasoning_tokens\":6}}",
                "{\"prompt_tokens\":6,\"completion_tokens\":6,\"prompt_cache_hit_tokens\":6,\"prompt_cache_miss_tokens\":6,\"completion_tokens_details\":{\"reasoning_tokens\":4}}");

        for (String current : decreasingUsages) {
            responseBody.set(
                    """
                    data: {"id":"tr-fields","model":"deepseek-v4-flash","choices":[{"index":0,"delta":{"content":"x"},"finish_reason":null}],"usage":{"prompt_tokens":5,"completion_tokens":5,"prompt_cache_hit_tokens":5,"prompt_cache_miss_tokens":5,"completion_tokens_details":{"reasoning_tokens":5}}}

                    data: {"id":"tr-fields","model":"deepseek-v4-flash","choices":[{"index":0,"delta":{"content":"y"},"finish_reason":"stop"}],"usage":%s}

                    data: [DONE]

                    """
                            .formatted(current));

            assertProviderCode("non_monotonic_stream_usage");
        }
    }

    @Test
    void freezesOfficialEndpointAndRejectsUnsafeEndpointsBeforeHttp() {
        assertThat(OpenAiCompatibleDialects.configuredOptions(
                        OpenAiCompatibleDialects.TOKENRHYTHM, URI.create("https://tokenrhythm.studio/v1")))
                .containsEntry("endpoint_host", "tokenrhythm.studio");
        assertThat(OpenAiCompatibleDialects.resolve(snapshot(provider)).streamUsageMode())
                .isEqualTo(StreamUsageMode.MONOTONIC_CUMULATIVE);

        assertThatThrownBy(() -> OpenAiCompatibleDialects.configuredOptions(
                        OpenAiCompatibleDialects.TOKENRHYTHM, URI.create("https://example.com/v1")))
                .hasMessageContaining("host is not allowed");
        assertThatThrownBy(() -> OpenAiCompatibleDialects.configuredOptions(
                        OpenAiCompatibleDialects.TOKENRHYTHM, URI.create("https://tokenrhythm.studio/api/v1")))
                .hasMessageContaining("/v1");
        assertThatThrownBy(() -> model(provider(
                        URI.create("http://tokenrhythm.studio/v1"), Map.of("endpoint_host", "tokenrhythm.studio"))))
                .hasMessageContaining("loopback host");
        assertThat(requests).hasValue(0);
    }

    private void assertProviderCode(String code) {
        assertThatThrownBy(() -> model(provider)
                        .invokeStreaming(
                                request(snapshot(provider), List.of()), ignored -> ModelStreamControl.CONTINUE))
                .isInstanceOf(ModelInvocationException.class)
                .satisfies(error -> {
                    ModelInvocationException failure = (ModelInvocationException) error;
                    assertThat(failure.category())
                            .isIn(ModelErrorCategory.MALFORMED_RESPONSE, ModelErrorCategory.PARTIAL_RESPONSE);
                    assertThat(failure.providerCode()).isEqualTo(code);
                });
    }

    private OpenAiCompatibleChatModel model(ModelProviderDefinition definition) {
        return new OpenAiCompatibleChatModel(
                definition,
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                json,
                ignored -> new ResolvedCredential("tokenrhythm-test-secret"),
                true,
                1024 * 1024);
    }

    private ModelProviderDefinition provider(URI endpoint, Map<String, Object> options) {
        ModelProviderId providerId = new ModelProviderId("tokenrhythm");
        ModelDefinition model = new ModelDefinition(
                new ModelDefinitionId("tokenrhythm-deepseek-v4-flash"),
                "model-v1",
                providerId,
                "deepseek-v4-flash",
                "TokenRhythm DeepSeek V4 Flash",
                ModelStatus.ACTIVE,
                EnumSet.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                1_000_000,
                8_192,
                Map.of(),
                Map.of(),
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS);
        return new ModelProviderDefinition(
                providerId,
                "provider-v1",
                "TokenRhythm",
                endpoint,
                new CredentialRef("env://TR_API_KEY"),
                true,
                ProviderStatus.ACTIVE,
                List.of(new ModelApiBindingDefinition(
                        ModelApiStyles.OPENAI_CHAT_COMPLETIONS, OpenAiCompatibleDialects.TOKENRHYTHM)),
                List.of(model),
                options,
                Map.of());
    }

    private ResolvedModelSnapshot snapshot(ModelProviderDefinition definition) {
        ModelDefinition model = definition.models().getFirst();
        return ResolvedModelSnapshot.create(
                definition.id(),
                definition.version(),
                model.id(),
                model.version(),
                model.providerModelId(),
                ModelApiStyles.OPENAI_CHAT_ADAPTER,
                "1.0.0",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                OpenAiCompatibleDialects.TOKENRHYTHM,
                definition.endpoint(),
                definition.credentialRef(),
                true,
                model.capabilities(),
                model.contextWindow(),
                model.maxOutputTokens(),
                definition.options(),
                Map.of());
    }

    private AgentChatRequest request(ResolvedModelSnapshot snapshot, List<ModelToolSpecification> tools) {
        return new AgentChatRequest(
                new ModelCallId("tokenrhythm-call"),
                new AgentRunId("tokenrhythm-run"),
                1,
                1,
                snapshot,
                List.of(ModelMessage.text(ModelMessageRole.USER, "hello")),
                tools,
                1024,
                Duration.ofSeconds(5),
                Map.of());
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] bytes = responseBody.get().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
