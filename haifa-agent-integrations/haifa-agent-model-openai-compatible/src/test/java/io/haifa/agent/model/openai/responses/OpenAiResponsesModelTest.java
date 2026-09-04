package io.haifa.agent.model.openai.responses;

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
import io.haifa.agent.model.api.ApiStyleId;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCallId;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelErrorCategory;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelInvocationException;
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelReasoningBehavior;
import io.haifa.agent.model.api.ModelReasoningEffort;
import io.haifa.agent.model.api.ModelReasoningMode;
import io.haifa.agent.model.api.ModelStreamControl;
import io.haifa.agent.model.api.ModelStreamEvent;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelToolSpecification;
import io.haifa.agent.model.api.ResolvedCredential;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.model.api.SensitiveModelReasoning;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenAiResponsesModelTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<Response> response = new AtomicReference<>();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sendsStandardItemRequestAndMapsTextAndUsage() throws Exception {
        response.set(
                Response.json(
                        200,
                        """
                {"id":"resp-1","object":"response","status":"completed","model":"gpt-test",
                 "output":[{"id":"msg-1","type":"message","role":"assistant","status":"completed",
                   "content":[{"type":"output_text","text":"ready","annotations":[]}]}],
                 "usage":{"input_tokens":12,"output_tokens":5,
                   "input_tokens_details":{"cached_tokens":3},
                   "output_tokens_details":{"reasoning_tokens":2}}}
                """));
        var tool = new ModelToolSpecification("lookup", "1", "Lookup", "schema", "1", Map.of("type", "object"), true);
        var call = new ModelToolCall(new ProviderToolCallCorrelationId("call-1"), "lookup", Map.of("city", "Hangzhou"));
        var request = request(
                standardSnapshot(true, Map.of("response_format", Map.of("type", "json_object"))),
                List.of(
                        ModelMessage.text(ModelMessageRole.SYSTEM, "Be concise"),
                        ModelMessage.text(ModelMessageRole.USER, "weather"),
                        ModelMessage.assistant("", List.of(call)),
                        ModelMessage.tool(new ProviderToolCallCorrelationId("call-1"), "sunny")),
                List.of(tool),
                Map.of());

        var actual = model().invoke(request);

        assertThat(actual.content()).isEqualTo("ready");
        assertThat(actual.usage().cacheHitTokens()).isEqualTo(3);
        assertThat(actual.usage().reasoningTokens()).isEqualTo(2);
        JsonNode sent = json.readTree(requestBody.get());
        assertThat(sent.path("instructions").asText()).isEqualTo("Be concise");
        assertThat(sent.path("store").asBoolean()).isFalse();
        assertThat(sent.path("tools").get(0).path("name").asText()).isEqualTo("lookup");
        assertThat(sent.path("tools").get(0).has("function")).isFalse();
        assertThat(sent.path("input").get(1).path("type").asText()).isEqualTo("function_call");
        assertThat(sent.path("input").get(2).path("type").asText()).isEqualTo("function_call_output");
        assertThat(sent.path("text").path("format").path("type").asText()).isEqualTo("json_object");
        assertThat(authorization.get()).isEqualTo("Bearer test-secret");
    }

    @Test
    void mapsFunctionCallOutputWithExactCallId() {
        response.set(
                Response.json(
                        200,
                        """
                {"id":"resp-tool","status":"completed","model":"gpt-test",
                 "output":[{"id":"fc-1","type":"function_call","status":"completed",
                   "call_id":"call-weather","name":"weather","arguments":"{\\\"city\\\":\\\"Paris\\\"}"}],
                 "usage":{"input_tokens":4,"output_tokens":3}}
                """));

        var actual = model().invoke(simpleRequest(standardSnapshot(true)));

        assertThat(actual.finishReason()).isEqualTo(ModelFinishReason.TOOL_CALLS);
        assertThat(actual.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.providerCorrelationId().value()).isEqualTo("call-weather");
            assertThat(call.arguments()).containsEntry("city", "Paris");
        });
    }

    @Test
    void replaysOriginalDeepSeekReasoningAlongsideFunctionResult() throws Exception {
        response.set(
                Response.json(
                        200,
                        """
                {"id":"resp-follow-up","status":"completed","model":"deepseek-v4-flash",
                 "output":[{"id":"msg-1","type":"message","role":"assistant","status":"completed",
                   "content":[{"type":"output_text","text":"done"}]}],
                 "usage":{"input_tokens":9,"output_tokens":2}}
                """));
        var call = new ModelToolCall(
                new ProviderToolCallCorrelationId("call-deepseek-1"), "lookup", Map.of("city", "Hangzhou"));
        var reasoning = SensitiveModelReasoning.of("controlled reasoning continuation");
        var request = request(
                deepSeekSnapshot(),
                List.of(
                        ModelMessage.text(ModelMessageRole.USER, "weather"),
                        ModelMessage.assistant("", List.of(call), reasoning),
                        ModelMessage.tool(call.providerCorrelationId(), "sunny")),
                List.of(new ModelToolSpecification(
                        "lookup", "1", "Lookup", "schema", "1", Map.of("type", "object"), false)),
                Map.of());

        model().invoke(request);

        JsonNode input = json.readTree(requestBody.get()).path("input");
        assertThat(input.get(1).path("type").asText()).isEqualTo("reasoning");
        assertThat(input.get(1).path("content").asText()).isEqualTo("controlled reasoning continuation");
        assertThat(input.get(1).path("content").asText()).doesNotContain("SensitiveModelReasoning[");
        assertThat(input.get(2).path("type").asText()).isEqualTo("function_call");
        assertThat(input.get(3).path("type").asText()).isEqualTo("function_call_output");
    }

    @Test
    void mapsFrozenRecordRequirementToResponsesTextFormatAndParsesTerminalOutput() throws Exception {
        response.set(Response.json(
                200,
                """
                {"id":"resp-structured","status":"completed","model":"gpt-test",
                 "output":[{"id":"msg-1","type":"message","content":[{"type":"output_text","text":%s}]}],
                 "usage":{"input_tokens":2,"output_tokens":1}}
                """
                        .formatted(json.writeValueAsString("{\"city\":\"Shanghai\",\"days\":2}"))));
        var requirement = new StructuredOutputRequirement(
                "java-record:TripPlan",
                "sha256:test",
                "TripPlan",
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of(
                                "city", Map.of("type", "string"),
                                "days", Map.of("type", "integer")),
                        "required",
                        List.of("city", "days"),
                        "additionalProperties",
                        false));
        AgentChatRequest request = new AgentChatRequest(
                new ModelCallId("call-structured"),
                new AgentRunId("run-structured"),
                1,
                1,
                standardSnapshot(true),
                List.of(ModelMessage.text(ModelMessageRole.USER, "plan")),
                List.of(),
                1024,
                Duration.ofSeconds(5),
                Map.of(),
                java.util.Optional.of(requirement));

        var actual = model().invoke(request);

        assertThat(actual.structuredOutput()).contains(Map.of("city", "Shanghai", "days", 2));
        JsonNode format = json.readTree(requestBody.get()).path("text").path("format");
        assertThat(format.path("type").asText()).isEqualTo("json_schema");
        assertThat(format.path("name").asText()).isEqualTo("TripPlan");
        assertThat(format.path("strict").asBoolean()).isTrue();
        assertThat(format.path("schema").path("additionalProperties").asBoolean())
                .isFalse();
    }

    @Test
    void streamsStandardTextAndCompletesFromTerminalResponse() {
        response.set(
                Response.sse(
                        """
                data: {"type":"response.created","response":{"id":"resp-stream","status":"in_progress"}}

                data: {"type":"response.output_text.delta","item_id":"msg-1","output_index":0,"content_index":0,"delta":"rea"}

                data: {"type":"response.output_text.delta","item_id":"msg-1","output_index":0,"content_index":0,"delta":"dy"}

                data: {"type":"response.completed","response":{"id":"resp-stream","status":"completed","model":"gpt-test","output":[{"id":"msg-1","type":"message","content":[{"type":"output_text","text":"ready"}]}],"usage":{"input_tokens":2,"output_tokens":1}}}

                """));
        List<ModelStreamEvent> events = new ArrayList<>();

        var actual = model().invokeStreaming(simpleRequest(standardSnapshot(true)), event -> {
            events.add(event);
            return ModelStreamControl.CONTINUE;
        });

        assertThat(actual.content()).isEqualTo("ready");
        assertThat(events)
                .extracting(event -> event.getClass().getSimpleName())
                .containsExactly("Started", "ContentDelta", "ContentDelta", "UsageReported");
    }

    @Test
    void interruptedStreamAfterTextIsANonRetryablePartialResponse() {
        response.set(
                Response.sse(
                        """
                data: {"type":"response.created","response":{"id":"resp-cut","status":"in_progress"}}

                data: {"type":"response.output_text.delta","item_id":"msg-1","output_index":0,"content_index":0,"delta":"partial"}

                """));

        assertThatThrownBy(() -> model().invokeStreaming(
                                simpleRequest(standardSnapshot(true)), event -> ModelStreamControl.CONTINUE))
                .isInstanceOf(ModelInvocationException.class)
                .satisfies(error -> {
                    ModelInvocationException failure = (ModelInvocationException) error;
                    assertThat(failure.category()).isEqualTo(ModelErrorCategory.PARTIAL_RESPONSE);
                    assertThat(failure.outputObserved()).isTrue();
                    assertThat(failure.retryable()).isFalse();
                });
    }

    @Test
    void acceptsEmptyTextAndReasoningDeltasFromCompatibleStreams() {
        response.set(
                Response.sse(
                        """
                data: {"type":"response.created","sequence_number":0,"response":{"id":"resp-empty-delta","status":"in_progress"}}

                data: {"type":"response.reasoning_text.delta","sequence_number":1,"item_id":"reasoning-1","output_index":0,"content_index":0,"delta":""}

                data: {"type":"response.output_text.delta","sequence_number":2,"item_id":"msg-1","output_index":1,"content_index":0,"delta":""}

                data: {"type":"response.completed","sequence_number":3,"response":{"id":"resp-empty-delta","status":"completed","model":"gpt-test","output":[{"id":"msg-1","type":"message","content":[{"type":"output_text","text":"ready"}]}],"usage":{"input_tokens":2,"output_tokens":1}}}

                """));
        List<ModelStreamEvent> events = new ArrayList<>();

        var actual = model().invokeStreaming(simpleRequest(standardSnapshot(true)), event -> {
            events.add(event);
            return ModelStreamControl.CONTINUE;
        });

        assertThat(actual.content()).isEqualTo("ready");
        assertThat(events)
                .extracting(event -> event.getClass().getSimpleName())
                .containsExactly("Started", "UsageReported");
    }

    @Test
    void bridgesSynchronousResponsesWhenProviderDisablesNativeStreaming() throws Exception {
        response.set(
                Response.json(
                        200,
                        """
                {"id":"resp-sync","status":"completed","model":"gpt-test",
                 "output":[{"id":"msg-1","type":"message","content":[{"type":"output_text","text":"ready"}]}],
                 "usage":{"input_tokens":2,"output_tokens":1}}
                """));
        List<ModelStreamEvent> events = new ArrayList<>();

        var actual = model().invokeStreaming(simpleRequest(standardSnapshot(false)), event -> {
            events.add(event);
            return ModelStreamControl.CONTINUE;
        });

        assertThat(actual.content()).isEqualTo("ready");
        assertThat(json.readTree(requestBody.get()).path("stream").asBoolean()).isFalse();
        assertThat(events)
                .extracting(event -> event.getClass().getSimpleName())
                .containsExactly("Started", "ContentDelta", "UsageReported");
    }

    @Test
    void deepSeekStreamRequiresMonotonicSequenceAndNoDoneSentinel() {
        response.set(
                Response.sse(
                        """
                data: {"type":"response.created","sequence_number":0,"response":{"id":"resp-ds","status":"in_progress"}}

                data: {"type":"response.output_text.delta","sequence_number":2,"item_id":"msg-1","output_index":0,"content_index":0,"delta":"ok"}

                data: {"type":"response.completed","sequence_number":3,"response":{"id":"resp-ds","status":"completed","model":"deepseek-v4-flash","output":[{"id":"msg-1","type":"message","content":[{"type":"output_text","text":"ok"}]}],"usage":{"input_tokens":2,"output_tokens":1}}}

                """));

        var actual = model().invokeStreaming(simpleRequest(deepSeekSnapshot()), event -> ModelStreamControl.CONTINUE);

        assertThat(actual.content()).isEqualTo("ok");

        response.set(
                Response.sse(
                        """
                data: {"type":"response.created","sequence_number":1,"response":{"id":"bad","status":"in_progress"}}

                data: {"type":"response.output_text.delta","sequence_number":1,"delta":"bad"}

                """));
        assertThatThrownBy(() -> model().invokeStreaming(
                                simpleRequest(deepSeekSnapshot()), event -> ModelStreamControl.CONTINUE))
                .isInstanceOf(ModelInvocationException.class)
                .extracting(value -> ((ModelInvocationException) value).category())
                .isEqualTo(ModelErrorCategory.MALFORMED_RESPONSE);
    }

    @Test
    void deepSeekRejectsUnknownModelAndNonAutomaticToolChoiceBeforeNetwork() {
        assertThatThrownBy(() -> model().invoke(simpleRequest(deepSeekSnapshot("deepseek-future"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not verified");
        var tool = new ModelToolSpecification("lookup", "1", "Lookup", "schema", "1", Map.of("type", "object"), false);
        assertThatThrownBy(() -> model().invoke(request(
                        deepSeekSnapshot(),
                        List.of(ModelMessage.text(ModelMessageRole.USER, "hello")),
                        List.of(tool),
                        Map.of("tool_choice", "required"))))
                .isInstanceOf(ModelInvocationException.class)
                .extracting(value -> ((ModelInvocationException) value).category())
                .isEqualTo(ModelErrorCategory.INVALID_REQUEST);
    }

    @Test
    void mapsHttpFailuresWithoutLeakingRawBodyOrCredential() {
        response.set(Response.json(429, "{\"error\":{\"message\":\"test-secret private prompt\"}}"));

        assertThatThrownBy(() -> model().invoke(simpleRequest(standardSnapshot(true))))
                .isInstanceOf(ModelInvocationException.class)
                .satisfies(error -> {
                    ModelInvocationException failure = (ModelInvocationException) error;
                    assertThat(failure.category()).isEqualTo(ModelErrorCategory.RATE_LIMITED);
                    assertThat(failure.getMessage()).doesNotContain("test-secret", "private prompt");
                });
    }

    @Test
    void rejectsOversizedSynchronousResponseAsMalformed() {
        response.set(Response.json(200, "{\"padding\":\"" + "x".repeat(256) + "\"}"));

        assertThatThrownBy(() -> model(64).invoke(simpleRequest(standardSnapshot(true))))
                .isInstanceOf(ModelInvocationException.class)
                .satisfies(error -> {
                    ModelInvocationException failure = (ModelInvocationException) error;
                    assertThat(failure.category()).isEqualTo(ModelErrorCategory.MALFORMED_RESPONSE);
                    assertThat(failure.providerCode()).isEqualTo("response_too_large");
                    assertThat(failure.getMessage()).doesNotContain("xxxx");
                });
    }

    private OpenAiResponsesModel model() {
        return model(1024 * 1024);
    }

    private OpenAiResponsesModel model(int maxResponseBytes) {
        return new OpenAiResponsesModel(
                HttpClient.newHttpClient(),
                json,
                ignored -> new ResolvedCredential("test-secret"),
                true,
                maxResponseBytes);
    }

    private AgentChatRequest simpleRequest(ResolvedModelSnapshot snapshot) {
        return request(snapshot, List.of(ModelMessage.text(ModelMessageRole.USER, "hello")), List.of(), Map.of());
    }

    private AgentChatRequest request(
            ResolvedModelSnapshot snapshot,
            List<ModelMessage> messages,
            List<ModelToolSpecification> tools,
            Map<String, Object> options) {
        return new AgentChatRequest(
                new ModelCallId("call-1"),
                new AgentRunId("run-1"),
                1,
                1,
                snapshot,
                messages,
                tools,
                1024,
                Duration.ofSeconds(5),
                options);
    }

    private ResolvedModelSnapshot standardSnapshot(boolean nativeStreaming) {
        return standardSnapshot(nativeStreaming, Map.of());
    }

    private ResolvedModelSnapshot standardSnapshot(boolean nativeStreaming, Map<String, Object> invocationOptions) {
        return snapshot("gpt-test", OpenAiResponsesDialects.STANDARD, nativeStreaming, invocationOptions);
    }

    private ResolvedModelSnapshot deepSeekSnapshot() {
        return deepSeekSnapshot("deepseek-v4-flash");
    }

    private ResolvedModelSnapshot deepSeekSnapshot(String providerModelId) {
        return snapshot("deepseek", providerModelId, OpenAiResponsesDialects.DEEPSEEK, true, Map.of());
    }

    private ResolvedModelSnapshot snapshot(String providerModelId, String dialect, boolean nativeStreaming) {
        return snapshot("stub", providerModelId, dialect, nativeStreaming, Map.of());
    }

    private ResolvedModelSnapshot snapshot(
            String providerModelId, String dialect, boolean nativeStreaming, Map<String, Object> invocationOptions) {
        return snapshot("stub", providerModelId, dialect, nativeStreaming, invocationOptions);
    }

    private ResolvedModelSnapshot snapshot(
            String providerId,
            String providerModelId,
            String dialect,
            boolean nativeStreaming,
            Map<String, Object> invocationOptions) {
        return ResolvedModelSnapshot.create(
                new io.haifa.agent.model.api.ModelProviderId(providerId),
                "provider-v1",
                new ModelDefinitionId("model"),
                "model-v1",
                providerModelId,
                OpenAiResponsesModel.ADAPTER_TYPE,
                OpenAiResponsesModel.ADAPTER_VERSION,
                new ApiStyleId("openai-responses"),
                dialect,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                new CredentialRef("env://TEST_KEY"),
                nativeStreaming,
                EnumSet.of(
                        ModelCapability.TEXT_CHAT,
                        ModelCapability.TOOL_CALLING,
                        ModelCapability.STRUCTURED_OUTPUT,
                        ModelCapability.REASONING),
                128_000,
                8_192,
                Map.of(),
                invocationOptions);
    }

    private void handle(HttpExchange exchange) throws IOException {
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        Response configured = response.get();
        exchange.getResponseHeaders().set("Content-Type", configured.contentType());
        byte[] body = configured.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(configured.status(), body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    @Test
    void rejectsDuplicateAdmissionKeyRegistrationInResponsesRegistry() {
        Map<OpenAiResponsesBindingRegistry.AdmissionKey, OpenAiResponsesBindingRegistry.AdmittedBinding> map =
                new HashMap<>();
        OpenAiResponsesBindingRegistry.register(
                map,
                "provider-test",
                "model-test",
                ModelApiStyles.OPENAI_RESPONSES,
                OpenAiResponsesDialects.DEEPSEEK,
                ModelReasoningBehavior.ALWAYS,
                Set.of(ModelReasoningMode.ENABLED),
                Set.of(ModelReasoningEffort.HIGH),
                false);
        assertThatThrownBy(() -> OpenAiResponsesBindingRegistry.register(
                        map,
                        "provider-test",
                        "model-test",
                        ModelApiStyles.OPENAI_RESPONSES,
                        OpenAiResponsesDialects.DEEPSEEK,
                        ModelReasoningBehavior.OPTIONAL,
                        Set.of(ModelReasoningMode.DISABLED),
                        Set.of(),
                        true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate model binding admission key");
    }

    @Test
    void rejectsImageInputForDeepSeekResponsesDialect() {
        var message = ModelMessage.user(
                "inspect", List.of(new io.haifa.agent.model.api.ImageDataPart("image/png", new byte[] {1, 2, 3})));
        var snapshot = deepSeekSnapshot();

        assertThatThrownBy(() -> new AgentChatRequest(
                        new ModelCallId("call-img"),
                        new AgentRunId("run-img"),
                        1,
                        1,
                        snapshot,
                        List.of(message),
                        List.of(),
                        1024,
                        Duration.ofSeconds(5),
                        Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not support image input");

        var imageSnapshot = ResolvedModelSnapshot.create(
                new io.haifa.agent.model.api.ModelProviderId("deepseek"),
                "provider-v1",
                new ModelDefinitionId("model"),
                "model-v1",
                "deepseek-v4-flash",
                OpenAiResponsesModel.ADAPTER_TYPE,
                OpenAiResponsesModel.ADAPTER_VERSION,
                new ApiStyleId("openai-responses"),
                OpenAiResponsesDialects.DEEPSEEK,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                new CredentialRef("env://TEST_KEY"),
                true,
                EnumSet.of(
                        ModelCapability.TEXT_CHAT,
                        ModelCapability.TOOL_CALLING,
                        ModelCapability.IMAGE_UPLOAD_INPUT,
                        ModelCapability.REASONING),
                128_000,
                8_192,
                Map.of(),
                Map.of());
        var request = new AgentChatRequest(
                new ModelCallId("call-img"),
                new AgentRunId("run-img"),
                1,
                1,
                imageSnapshot,
                List.of(message),
                List.of(),
                1024,
                Duration.ofSeconds(5),
                Map.of());

        assertThatThrownBy(() -> model().invoke(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DeepSeek Responses image input is not verified");
    }

    private record Response(int status, String contentType, String body) {
        private static Response json(int status, String body) {
            return new Response(status, "application/json", body);
        }

        private static Response sse(String body) {
            return new Response(200, "text/event-stream", body);
        }
    }
}
