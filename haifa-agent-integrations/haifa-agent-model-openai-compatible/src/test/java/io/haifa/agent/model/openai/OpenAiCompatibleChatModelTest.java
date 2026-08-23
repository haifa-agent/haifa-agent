package io.haifa.agent.model.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.StructuredOutputRequirement;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ImageDataPart;
import io.haifa.agent.model.api.ImageUrlPart;
import io.haifa.agent.model.api.ModelApiBindingDefinition;
import io.haifa.agent.model.api.ModelApiStyles;
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
import io.haifa.agent.model.api.ModelStreamControl;
import io.haifa.agent.model.api.ModelStreamEvent;
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
import java.util.ArrayList;
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
        server.createContext("/v1/chat/completions", this::handle);
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
    void sendsStandardOpenAiChatCompletionsRequestWithoutVendorExtensions() throws Exception {
        provider = openAiProvider(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"));
        response.set(
                Response.json(
                        200,
                        """
                {"id":"resp-openai","model":"gpt-5.6-luna",
                 "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"ready"}}],
                 "usage":{"prompt_tokens":4,"completion_tokens":2,"total_tokens":6}}
                """));

        var actual = model().invoke(openAiRequest());

        assertThat(actual.content()).isEqualTo("ready");
        JsonNode sent = json.readTree(requestBody.get());
        assertThat(sent.path("model").asText()).isEqualTo("gpt-5.6-luna");
        assertThat(sent.has("thinking")).isFalse();
        assertThat(sent.has("reasoning_effort")).isFalse();
        assertThat(authorization.get()).isEqualTo("Bearer test-secret");
    }

    @Test
    void appliesTypedSamplingOptionFrozenInTheModelSnapshot() throws Exception {
        provider = openAiProvider(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"));
        response.set(
                Response.json(
                        200,
                        """
                {"id":"resp-temperature","model":"gpt-5.6-luna",
                 "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"ready"}}],
                 "usage":{"prompt_tokens":4,"completion_tokens":2,"total_tokens":6}}
                """));
        AgentChatRequest request = new AgentChatRequest(
                new ModelCallId("call-temperature"),
                new AgentRunId("run-temperature"),
                1,
                1,
                openAiSnapshot(Map.of("temperature", 0.25d)),
                List.of(ModelMessage.text(ModelMessageRole.USER, "hello")),
                List.of(),
                1024,
                Duration.ofSeconds(5),
                Map.of());

        model().invoke(request);

        assertThat(json.readTree(requestBody.get()).path("temperature").asDouble())
                .isEqualTo(0.25d);
    }

    @Test
    void mapsFrozenRecordRequirementToJsonSchemaAndParsesTheStructuredFinalObject() throws Exception {
        provider = openAiProvider(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"));
        response.set(Response.json(
                200,
                """
                {"id":"resp-structured","model":"gpt-5.6-luna",
                 "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":%s}}],
                 "usage":{"prompt_tokens":4,"completion_tokens":2,"total_tokens":6}}
                """
                        .formatted(json.writeValueAsString("{\"city\":\"Shanghai\",\"days\":2}"))));
        var requirement = requirement();
        AgentChatRequest request = new AgentChatRequest(
                new ModelCallId("call-structured"),
                new AgentRunId("run-structured"),
                1,
                1,
                openAiSnapshot(),
                List.of(ModelMessage.text(ModelMessageRole.USER, "plan")),
                List.of(),
                1024,
                Duration.ofSeconds(5),
                Map.of(),
                java.util.Optional.of(requirement));

        var actual = model().invoke(request);

        assertThat(actual.structuredOutput()).contains(Map.of("city", "Shanghai", "days", 2));
        JsonNode format = json.readTree(requestBody.get()).path("response_format");
        assertThat(format.path("type").asText()).isEqualTo("json_schema");
        assertThat(format.path("json_schema").path("name").asText()).isEqualTo("TripPlan");
        assertThat(format.path("json_schema").path("strict").asBoolean()).isTrue();
        assertThat(format.path("json_schema")
                        .path("schema")
                        .path("required")
                        .get(1)
                        .asText())
                .isEqualTo("days");
    }

    @Test
    void keepsDeepSeekToolTurnsAvailableWhileUsingJsonObjectModeForTheFinalSchema() throws Exception {
        response.set(Response.json(
                200,
                """
                {"id":"resp-tool","model":"deepseek-v4-pro",
                 "choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
                   "tool_calls":[{"id":"call-weather","type":"function","function":{"name":"weather","arguments":%s}}]}}],
                 "usage":{"prompt_tokens":4,"completion_tokens":2,"total_tokens":6}}
                """
                        .formatted(json.writeValueAsString("{\"city\":\"Shanghai\"}"))));
        var tool = new ModelToolSpecification(
                "weather", "1", "Weather", "weather-input", "1", Map.of("type", "object"), false);
        AgentChatRequest request = new AgentChatRequest(
                new ModelCallId("call-structured-tool"),
                new AgentRunId("run-structured-tool"),
                1,
                1,
                snapshot(),
                List.of(ModelMessage.text(ModelMessageRole.USER, "plan")),
                List.of(tool),
                1024,
                Duration.ofSeconds(5),
                Map.of(),
                java.util.Optional.of(requirement()));

        var actual = model().invoke(request);

        assertThat(actual.toolCalls()).hasSize(1);
        assertThat(actual.structuredOutput()).isEmpty();
        JsonNode sent = json.readTree(requestBody.get());
        assertThat(sent.path("response_format").path("type").asText()).isEqualTo("json_object");
        assertThat(sent.path("messages").get(1).path("role").asText()).isEqualTo("developer");
        assertThat(sent.path("messages").get(1).path("content").asText()).contains("additionalProperties");
    }

    @Test
    void mapsRemoteAndUploadedImagesToStandardChatCompletionsContentParts() throws Exception {
        provider = openAiProvider(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"));
        response.set(
                Response.json(
                        200,
                        """
                {"id":"resp-image","model":"gpt-5.6-luna",
                 "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"two images"}}],
                 "usage":{"prompt_tokens":8,"completion_tokens":2,"total_tokens":10}}
                """));
        ModelMessage message = ModelMessage.user(
                "describe both images",
                List.of(
                        new ImageUrlPart(URI.create("https://images.example.com/cat.png")),
                        new ImageDataPart("image/png", new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47})));

        var actual = model().invoke(new AgentChatRequest(
                new ModelCallId("call-image"),
                new AgentRunId("run-image"),
                1,
                1,
                openAiSnapshot(),
                List.of(message),
                List.of(),
                1024,
                Duration.ofSeconds(5),
                Map.of()));

        assertThat(actual.content()).isEqualTo("two images");
        JsonNode content =
                json.readTree(requestBody.get()).path("messages").get(0).path("content");
        assertThat(content.isArray()).isTrue();
        assertThat(content.get(0).path("type").asText()).isEqualTo("text");
        assertThat(content.get(0).path("text").asText()).isEqualTo("describe both images");
        assertThat(content.get(1).path("type").asText()).isEqualTo("image_url");
        assertThat(content.get(1).path("image_url").path("url").asText())
                .isEqualTo("https://images.example.com/cat.png");
        assertThat(content.get(2).path("image_url").path("url").asText()).isEqualTo("data:image/png;base64,iVBORw==");
    }

    @Test
    void bridgesStandardOpenAiSynchronousTransportIntoModelStreamEvents() throws Exception {
        provider = openAiProvider(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"), false, Map.of());
        response.set(
                Response.json(
                        200,
                        """
                {"id":"resp-openai-sync","model":"gpt-5.6-luna",
                 "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"ready"}}],
                 "usage":{"prompt_tokens":4,"completion_tokens":2,"total_tokens":6}}
                """));
        List<ModelStreamEvent> events = new ArrayList<>();

        var actual = model().invokeStreaming(openAiRequest(), event -> {
            events.add(event);
            return ModelStreamControl.CONTINUE;
        });

        assertThat(actual.content()).isEqualTo("ready");
        assertThat(events)
                .extracting(event -> event.getClass().getSimpleName())
                .containsExactly("Started", "ContentDelta", "UsageReported");
        JsonNode sent = json.readTree(requestBody.get());
        assertThat(sent.path("stream").asBoolean()).isFalse();
        assertThat(sent.has("stream_options")).isFalse();
    }

    @Test
    void refusesInsecureNonLoopbackOpenAiEndpointEvenWhenLocalHttpIsEnabled() {
        provider = openAiProvider(URI.create("http://example.com/v1"));

        assertThatThrownBy(this::model)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loopback host");
    }

    @Test
    void acceptsTrustedThirdPartyHttpsHostForStandardChatCompletions() {
        provider = openAiProvider(
                URI.create("https://gateway.example.com/v1"), true, Map.of("endpoint_host", "gateway.example.com"));

        model();
    }

    @Test
    void rejectsThirdPartyHttpsHostThatDoesNotMatchFrozenTrustedHost() {
        provider = openAiProvider(
                URI.create("https://gateway.example.com/v1"), true, Map.of("endpoint_host", "other.example.com"));

        assertThatThrownBy(this::model)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provider endpoint host is not allowed");
    }

    @Test
    void streamsReasoningContentAndUsageWithoutReturningRawReasoning() throws Exception {
        response.set(
                Response.sse(
                        """
                data: {"id":"stream-1","model":"deepseek-v4-pro","choices":[{"index":0,"delta":{"reasoning_content":"secret thought"},"finish_reason":null}]}

                data: {"id":"stream-1","model":"deepseek-v4-pro","choices":[{"index":0,"delta":{"content":"hel"},"finish_reason":null}]}

                data: {"id":"stream-1","model":"deepseek-v4-pro","choices":[{"index":0,"delta":{"content":"lo"},"finish_reason":"stop"}]}

                data: {"id":"stream-1","model":"deepseek-v4-pro","choices":[],"usage":{"prompt_tokens":2,"completion_tokens":3}}

                data: [DONE]

                """));
        List<ModelStreamEvent> events = new ArrayList<>();

        var result = model().invokeStreaming(simpleRequest(), event -> {
            events.add(event);
            return ModelStreamControl.CONTINUE;
        });

        assertThat(result.content()).isEqualTo("hello");
        assertThat(result.metadata()).containsEntry("reasoningCharacters", 14);
        assertThat(result.toString()).doesNotContain("secret thought");
        assertThat(events)
                .extracting(event -> event.getClass().getSimpleName())
                .containsExactly("Started", "ReasoningDelta", "ContentDelta", "ContentDelta", "UsageReported");
        assertThat(events.get(1).toString()).doesNotContain("secret thought");
        JsonNode sent = json.readTree(requestBody.get());
        assertThat(sent.path("stream").asBoolean()).isTrue();
        assertThat(sent.path("stream_options").path("include_usage").asBoolean())
                .isTrue();
    }

    @Test
    void assemblesStreamedToolCallFragmentsByStableIndex() {
        response.set(
                Response.sse(
                        """
                data: {"id":"stream-tool","model":"deepseek-v4-pro","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"provider-call-1","type":"function","function":{"name":"weather","arguments":"{\\\"city\\\":"}}]},"finish_reason":null}]}

                data: {"id":"stream-tool","model":"deepseek-v4-pro","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\\"Paris\\\"}"}}]},"finish_reason":"tool_calls"}]}

                data: {"id":"stream-tool","model":"deepseek-v4-pro","choices":[],"usage":{"prompt_tokens":4,"completion_tokens":2}}

                data: [DONE]

                """));
        List<ModelStreamEvent> events = new ArrayList<>();

        var result = model().invokeStreaming(simpleRequest(), event -> {
            events.add(event);
            return ModelStreamControl.CONTINUE;
        });

        assertThat(result.finishReason()).isEqualTo(ModelFinishReason.TOOL_CALLS);
        assertThat(result.toolCalls()).singleElement().satisfies(tool -> {
            assertThat(tool.providerCorrelationId().value()).isEqualTo("provider-call-1");
            assertThat(tool.name()).isEqualTo("weather");
            assertThat(tool.arguments()).containsEntry("city", "Paris");
        });
        assertThat(events)
                .filteredOn(ModelStreamEvent.ToolCallDelta.class::isInstance)
                .hasSize(2);
    }

    @Test
    void closesStreamWhenConsumerCancels() {
        response.set(
                Response.sse(
                        """
                data: {"id":"stream-cancel","model":"deepseek-v4-pro","choices":[{"index":0,"delta":{"content":"first"},"finish_reason":null}]}

                data: {"id":"stream-cancel","model":"deepseek-v4-pro","choices":[{"index":0,"delta":{"content":"second"},"finish_reason":"stop"}]}

                data: [DONE]

                """));

        assertThatThrownBy(() -> model().invokeStreaming(
                                simpleRequest(),
                                event -> event instanceof ModelStreamEvent.ContentDelta
                                        ? ModelStreamControl.CANCEL
                                        : ModelStreamControl.CONTINUE))
                .isInstanceOf(ModelInvocationException.class)
                .satisfies(error -> assertThat(((ModelInvocationException) error).category())
                        .isEqualTo(ModelErrorCategory.CANCELLED));
    }

    @Test
    void rejectsStreamThatEndsWithoutDoneSentinel() {
        response.set(
                Response.sse(
                        """
                data: {"id":"stream-cut","model":"deepseek-v4-pro","choices":[{"index":0,"delta":{"content":"partial"},"finish_reason":"stop"}]}

                """));

        assertThatThrownBy(() -> model().invokeStreaming(simpleRequest(), ignored -> ModelStreamControl.CONTINUE))
                .isInstanceOf(ModelInvocationException.class)
                .satisfies(error -> {
                    ModelInvocationException failure = (ModelInvocationException) error;
                    assertThat(failure.providerCode()).isEqualTo("stream_ended_without_done");
                    assertThat(failure.category()).isEqualTo(ModelErrorCategory.PARTIAL_RESPONSE);
                    assertThat(failure.outputObserved()).isTrue();
                    assertThat(failure.retryable()).isFalse();
                });
    }

    @Test
    void streamEndingBeforeConsumableOutputRemainsRetryableTransportFailure() {
        response.set(
                Response.sse(
                        """
                data: {"id":"stream-cut","model":"deepseek-v4-pro","choices":[{"index":0,"delta":{"role":"assistant"},"finish_reason":null}]}

                """));

        assertThatThrownBy(() -> model().invokeStreaming(simpleRequest(), ignored -> ModelStreamControl.CONTINUE))
                .isInstanceOf(ModelInvocationException.class)
                .satisfies(error -> {
                    ModelInvocationException failure = (ModelInvocationException) error;
                    assertThat(failure.category()).isEqualTo(ModelErrorCategory.TRANSPORT_ERROR);
                    assertThat(failure.outputObserved()).isFalse();
                    assertThat(failure.retryable()).isTrue();
                });
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
                ModelMessage.tool(
                        new ProviderToolCallCorrelationId("call-1"), "sunny", Map.of("temperatureCelsius", 24), true));
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
        JsonNode toolResult =
                json.readTree(sent.path("messages").get(2).path("content").asText());
        assertThat(toolResult.path("summary").asText()).isEqualTo("sunny");
        assertThat(toolResult.path("structuredData").path("temperatureCelsius").asInt())
                .isEqualTo(24);
        assertThat(toolResult.path("truncated").asBoolean()).isTrue();
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
                500, ModelErrorCategory.SERVER_ERROR,
                503, ModelErrorCategory.SERVER_ERROR);
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
    void preservesTrustedRetryAfterAsTypedMetadata() {
        response.set(Response.json(429, "{\"error\":{\"code\":\"rate_limited\"}}", Map.of("Retry-After", "5")));

        assertThatThrownBy(() -> model().invoke(simpleRequest()))
                .isInstanceOf(ModelInvocationException.class)
                .satisfies(error -> assertThat(((ModelInvocationException) error).retryAfter())
                        .contains(Duration.ofSeconds(5)));
    }

    @Test
    void rejectsInvalidJsonChoicesArgumentsContentTypeAndOversizedBodies() {
        response.set(Response.json(200, "not-json"));
        assertMalformed();
        response.set(Response.json(200, "{\"id\":\"x\",\"model\":\"deepseek-v4-pro\",\"choices\":[],\"usage\":{}}"));
        assertThatThrownBy(() -> model().invoke(simpleRequest()))
                .isInstanceOf(ModelInvocationException.class)
                .satisfies(error -> {
                    ModelInvocationException invocation = (ModelInvocationException) error;
                    assertThat(invocation.category()).isEqualTo(ModelErrorCategory.EMPTY_RESPONSE);
                    assertThat(invocation.retryable()).isTrue();
                });
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
                .hasMessageContaining("too long")
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
                Map.of("unsupported_option", 0));
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
                    assertThat(invocation.category()).isEqualTo(ModelErrorCategory.TRANSPORT_ERROR);
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
    void acceptsThinkingEnabledProviderAndRejectsUnsupportedEffort() {
        ModelProviderDefinition enabled = new ModelProviderDefinition(
                provider.id(),
                provider.version(),
                provider.displayName(),
                provider.endpoint(),
                provider.credentialRef(),
                provider.nativeStreaming(),
                provider.status(),
                provider.apiBindings(),
                provider.models(),
                Map.of("thinking", "enabled", "reasoning_effort", "high"),
                Map.of());
        new OpenAiCompatibleChatModel(
                enabled, HttpClient.newHttpClient(), json, ignored -> new ResolvedCredential("secret"), true, 1024);
        ModelProviderDefinition invalid = new ModelProviderDefinition(
                enabled.id(),
                enabled.version(),
                enabled.displayName(),
                enabled.endpoint(),
                enabled.credentialRef(),
                enabled.nativeStreaming(),
                enabled.status(),
                enabled.apiBindings(),
                enabled.models(),
                Map.of("thinking", "enabled", "reasoning_effort", "medium"),
                Map.of());
        assertThatThrownBy(() -> new OpenAiCompatibleChatModel(
                        invalid,
                        HttpClient.newHttpClient(),
                        json,
                        ignored -> new ResolvedCredential("secret"),
                        true,
                        1024))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("high or max");

        EnvironmentCredentialResolver resolver =
                new EnvironmentCredentialResolver(name -> name.equals("DEEPSEEK_API_KEY") ? "resolved-secret" : null);
        assertThat(resolver.resolve(new CredentialRef("env://DEEPSEEK_API_KEY")).value())
                .isEqualTo("resolved-secret");
        assertThatThrownBy(() -> resolver.resolve(new CredentialRef("file://secret")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scheme");
    }

    @Test
    void sendsEnabledHighThinkingAndProtectsSynchronousReasoning() throws Exception {
        response.set(
                Response.json(
                        200,
                        """
                {"id":"thinking-1","model":"deepseek-v4-pro","choices":[{"finish_reason":"stop",
                 "message":{"content":"answer","reasoning_content":"private chain"}}],
                 "usage":{"prompt_tokens":3,"completion_tokens":4,
                          "completion_tokens_details":{"reasoning_tokens":2}}}
                """));
        AgentChatRequest request = new AgentChatRequest(
                new ModelCallId("thinking-call"),
                new AgentRunId("thinking-run"),
                1,
                1,
                reasoningSnapshot(),
                List.of(ModelMessage.text(ModelMessageRole.USER, "hello")),
                List.of(),
                1024,
                Duration.ofSeconds(5),
                Map.of());

        var result = model().invoke(request);

        JsonNode sent = json.readTree(requestBody.get());
        assertThat(sent.path("thinking").path("type").asText()).isEqualTo("enabled");
        assertThat(sent.path("reasoning_effort").asText()).isEqualTo("high");
        assertThat(result.reasoning()).isPresent();
        assertThat(result.reasoning().orElseThrow().use(java.util.function.Function.identity()))
                .isEqualTo("private chain");
        assertThat(result.toString()).doesNotContain("private chain");
        assertThat(result.usage().reasoningTokens()).isEqualTo(2);
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

    private AgentChatRequest openAiRequest() {
        return new AgentChatRequest(
                new ModelCallId("call-openai"),
                new AgentRunId("run-openai"),
                1,
                1,
                openAiSnapshot(),
                List.of(ModelMessage.text(ModelMessageRole.USER, "hello")),
                List.of(),
                1024,
                Duration.ofSeconds(5),
                Map.of());
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
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                OpenAiCompatibleDialects.DEEPSEEK,
                provider.endpoint(),
                provider.credentialRef(),
                provider.nativeStreaming(),
                EnumSet.allOf(ModelCapability.class),
                1_048_576,
                393_216,
                provider.options(),
                Map.of("thinking", "disabled"));
    }

    private ResolvedModelSnapshot reasoningSnapshot() {
        return ResolvedModelSnapshot.create(
                provider.id(),
                provider.version(),
                new ModelDefinitionId("deepseek-v4-pro"),
                "model-v1",
                "deepseek-v4-pro",
                "openai-compatible",
                "1.0.0",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                OpenAiCompatibleDialects.DEEPSEEK,
                provider.endpoint(),
                provider.credentialRef(),
                provider.nativeStreaming(),
                EnumSet.allOf(ModelCapability.class),
                1_048_576,
                393_216,
                Map.of("thinking", "enabled", "reasoning_effort", "high"),
                Map.of("thinking", "enabled", "reasoning_effort", "high"));
    }

    private ResolvedModelSnapshot openAiSnapshot() {
        return openAiSnapshot(Map.of());
    }

    private ResolvedModelSnapshot openAiSnapshot(Map<String, Object> invocationOptions) {
        return ResolvedModelSnapshot.create(
                provider.id(),
                provider.version(),
                new ModelDefinitionId("openai-gpt-5.6-luna"),
                "model-v1",
                "gpt-5.6-luna",
                "openai-compatible",
                "1.0.0",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                ModelApiBindingDefinition.STANDARD_DIALECT,
                provider.endpoint(),
                provider.credentialRef(),
                provider.nativeStreaming(),
                EnumSet.of(
                        ModelCapability.TEXT_CHAT,
                        ModelCapability.IMAGE_INPUT,
                        ModelCapability.TOOL_CALLING,
                        ModelCapability.STRUCTURED_OUTPUT),
                128_000,
                8_192,
                provider.options(),
                invocationOptions);
    }

    private static StructuredOutputRequirement requirement() {
        return new StructuredOutputRequirement(
                "java-record:TripPlan",
                "sha256:test",
                "TripPlan",
                Map.of(
                        "type",
                        "object",
                        "additionalProperties",
                        false,
                        "properties",
                        Map.of(
                                "city", Map.of("type", "string"),
                                "days", Map.of("type", "integer")),
                        "required",
                        List.of("city", "days")));
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
                Map.of(),
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS);
        return new ModelProviderDefinition(
                providerId,
                "provider-v1",
                "DeepSeek",
                endpoint,
                new CredentialRef("env://DEEPSEEK_API_KEY"),
                true,
                ProviderStatus.ACTIVE,
                List.of(new ModelApiBindingDefinition(
                        ModelApiStyles.OPENAI_CHAT_COMPLETIONS, OpenAiCompatibleDialects.DEEPSEEK)),
                List.of(model),
                Map.of("thinking", "disabled"),
                Map.of());
    }

    private ModelProviderDefinition openAiProvider(URI endpoint) {
        return openAiProvider(endpoint, true, Map.of());
    }

    private ModelProviderDefinition openAiProvider(
            URI endpoint, boolean nativeStreaming, Map<String, Object> providerOptions) {
        ModelProviderId providerId = new ModelProviderId("openai");
        ModelDefinition model = new ModelDefinition(
                new ModelDefinitionId("openai-gpt-5.6-luna"),
                "model-v1",
                providerId,
                "gpt-5.6-luna",
                "GPT-5.6 Luna",
                ModelStatus.ACTIVE,
                EnumSet.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                128_000,
                8_192,
                Map.of(),
                Map.of(),
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS);
        return new ModelProviderDefinition(
                providerId,
                "provider-v1",
                "OpenAI",
                endpoint,
                new CredentialRef("env://OPENAI_API_KEY"),
                nativeStreaming,
                ProviderStatus.ACTIVE,
                List.of(new ModelApiBindingDefinition(ModelApiStyles.OPENAI_CHAT_COMPLETIONS)),
                List.of(model),
                providerOptions,
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
        selected.headers()
                .forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
        exchange.sendResponseHeaders(selected.status(), bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record Response(
            int status, String contentType, String body, long delayMillis, Map<String, String> headers) {
        Response(int status, String contentType, String body) {
            this(status, contentType, body, 0, Map.of());
        }

        Response(int status, String contentType, String body, long delayMillis) {
            this(status, contentType, body, delayMillis, Map.of());
        }

        static Response json(int status, String body) {
            return new Response(status, "application/json; charset=utf-8", body);
        }

        static Response json(int status, String body, Map<String, String> headers) {
            return new Response(status, "application/json; charset=utf-8", body, 0, Map.copyOf(headers));
        }

        static Response delayedJson(int status, String body, long delayMillis) {
            return new Response(status, "application/json; charset=utf-8", body, delayMillis);
        }

        static Response sse(String body) {
            return new Response(200, "text/event-stream; charset=utf-8", body);
        }
    }
}
