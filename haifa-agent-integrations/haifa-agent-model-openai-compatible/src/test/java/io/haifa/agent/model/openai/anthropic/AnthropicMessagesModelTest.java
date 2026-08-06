package io.haifa.agent.model.openai.anthropic;

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
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCallId;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelErrorCategory;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelInvocationException;
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelStreamControl;
import io.haifa.agent.model.api.ModelStreamEvent;
import io.haifa.agent.model.api.ModelToolSpecification;
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

class AnthropicMessagesModelTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> apiKey = new AtomicReference<>();
    private final AtomicReference<String> version = new AtomicReference<>();
    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private final AtomicReference<Response> response = new AtomicReference<>();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sendsStandardMessagesHeadersToolsAndUsage() throws Exception {
        response.set(
                Response.json(
                        200,
                        """
                {"id":"msg-1","type":"message","role":"assistant","model":"claude-test",
                 "content":[{"type":"text","text":"ready"}],"stop_reason":"end_turn","stop_sequence":null,
                 "usage":{"input_tokens":12,"output_tokens":5,"cache_read_input_tokens":3}}
                """));
        var tool = new ModelToolSpecification("lookup", "1", "Lookup", "schema", "1", Map.of("type", "object"), true);
        var request = request(
                standardSnapshot(true, Map.of()),
                List.of(
                        ModelMessage.text(ModelMessageRole.SYSTEM, "Be concise"),
                        ModelMessage.text(ModelMessageRole.USER, "weather"),
                        ModelMessage.assistant(
                                "",
                                List.of(new io.haifa.agent.model.api.ModelToolCall(
                                        new ProviderToolCallCorrelationId("toolu-1"),
                                        "lookup",
                                        Map.of("city", "Hangzhou")))),
                        ModelMessage.tool(new ProviderToolCallCorrelationId("toolu-1"), "sunny")),
                List.of(tool),
                Map.of("tool_choice", Map.of("type", "tool", "name", "lookup")));

        var actual = model().invoke(request);

        assertThat(actual.content()).isEqualTo("ready");
        assertThat(actual.usage().cacheHitTokens()).isEqualTo(3);
        JsonNode sent = json.readTree(requestBody.get());
        assertThat(requestPath.get()).isEqualTo("/v1/messages");
        assertThat(apiKey.get()).isEqualTo("test-secret");
        assertThat(version.get()).isEqualTo(AnthropicMessagesModel.ANTHROPIC_VERSION);
        assertThat(sent.path("system").asText()).isEqualTo("Be concise");
        assertThat(sent.path("max_tokens").asInt()).isEqualTo(1024);
        assertThat(sent.path("tools").get(0).path("input_schema").path("type").asText())
                .isEqualTo("object");
        assertThat(sent.path("tools").get(0).has("strict")).isFalse();
        assertThat(sent.path("tool_choice").path("name").asText()).isEqualTo("lookup");
        assertThat(sent.path("messages")
                        .get(1)
                        .path("content")
                        .get(0)
                        .path("type")
                        .asText())
                .isEqualTo("tool_use");
        assertThat(sent.path("messages")
                        .get(2)
                        .path("content")
                        .get(0)
                        .path("tool_use_id")
                        .asText())
                .isEqualTo("toolu-1");
    }

    @Test
    void protectsThinkingAndRoundTripsItWithToolResults() throws Exception {
        response.set(
                Response.json(
                        200,
                        """
                {"id":"msg-tool","type":"message","role":"assistant","model":"claude-test",
                 "content":[
                   {"type":"thinking","thinking":"private plan","signature":"opaque-signature"},
                   {"type":"redacted_thinking","data":"opaque-redacted-data"},
                   {"type":"tool_use","id":"toolu-weather","name":"weather","input":{"city":"Paris"}}],
                 "stop_reason":"tool_use","usage":{"input_tokens":4,"output_tokens":3}}
                """));

        var first = model().invoke(simpleRequest(standardSnapshot(true, Map.of())));

        assertThat(first.content()).isEmpty();
        assertThat(first.finishReason()).isEqualTo(ModelFinishReason.TOOL_CALLS);
        assertThat(first.reasoning()).isPresent();
        assertThat(first.toString()).doesNotContain("private plan", "opaque-signature");

        response.set(
                Response.json(
                        200,
                        """
                {"id":"msg-final","type":"message","role":"assistant","model":"claude-test",
                 "content":[{"type":"text","text":"sunny"}],"stop_reason":"end_turn",
                 "usage":{"input_tokens":8,"output_tokens":2}}
                """));
        var followUp = request(
                standardSnapshot(true, Map.of()),
                List.of(
                        ModelMessage.text(ModelMessageRole.USER, "weather"),
                        ModelMessage.assistant(
                                "", first.toolCalls(), first.reasoning().orElseThrow()),
                        ModelMessage.tool(new ProviderToolCallCorrelationId("toolu-weather"), "sunny")),
                List.of(new ModelToolSpecification(
                        "weather", "1", "Weather", "schema", "1", Map.of("type", "object"), false)),
                Map.of());

        assertThat(model().invoke(followUp).content()).isEqualTo("sunny");
        JsonNode sent = json.readTree(requestBody.get());
        JsonNode blocks = sent.path("messages").get(1).path("content");
        assertThat(blocks.get(0).path("type").asText()).isEqualTo("thinking");
        assertThat(blocks.get(0).path("signature").asText()).isEqualTo("opaque-signature");
        assertThat(blocks.get(1).path("type").asText()).isEqualTo("redacted_thinking");
        assertThat(blocks.get(2).path("id").asText()).isEqualTo("toolu-weather");
    }

    @Test
    void accumulatesNamedSseContentBlocksToolJsonThinkingAndUsage() {
        response.set(
                Response.sse(
                        """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg-stream","type":"message","role":"assistant","model":"claude-test","content":[],"stop_reason":null,"usage":{"input_tokens":9,"output_tokens":1,"cache_read_input_tokens":2}}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"plan"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"opaque"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: content_block_start
                data: {"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"ready"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":1}

                event: content_block_start
                data: {"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu-1","name":"lookup","input":{}}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\\\"city\\\":"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"\\\"Paris\\\"}"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":2}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null},"usage":{"output_tokens":7}}

                event: message_stop
                data: {"type":"message_stop"}

                """));
        List<ModelStreamEvent> events = new ArrayList<>();

        var actual = model().invokeStreaming(simpleRequest(standardSnapshot(true, Map.of())), event -> {
            events.add(event);
            return ModelStreamControl.CONTINUE;
        });

        assertThat(actual.content()).isEqualTo("ready");
        assertThat(actual.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.providerCorrelationId().value()).isEqualTo("toolu-1");
            assertThat(call.arguments()).containsEntry("city", "Paris");
        });
        assertThat(actual.reasoning()).isPresent();
        assertThat(actual.usage().inputTokens()).isEqualTo(9);
        assertThat(actual.usage().outputTokens()).isEqualTo(7);
        assertThat(events)
                .extracting(event -> event.getClass().getSimpleName())
                .containsExactly(
                        "Started", "ReasoningDelta", "ContentDelta", "ToolCallDelta", "ToolCallDelta", "UsageReported");
    }

    @Test
    void bridgesSynchronousMessageWhenNativeStreamingIsDisabled() throws Exception {
        response.set(
                Response.json(
                        200,
                        """
                {"id":"msg-sync","type":"message","role":"assistant","model":"claude-test",
                 "content":[{"type":"text","text":"ready"}],"stop_reason":"end_turn",
                 "usage":{"input_tokens":2,"output_tokens":1}}
                """));
        List<ModelStreamEvent> events = new ArrayList<>();

        var actual = model().invokeStreaming(simpleRequest(standardSnapshot(false, Map.of())), event -> {
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
    void appliesDeepSeekEndpointProfileAndDisablesThinkingWithoutClaimingIgnoredFields() throws Exception {
        response.set(
                Response.json(
                        200,
                        """
                {"id":"msg-ds","type":"message","role":"assistant","model":"deepseek-v4-flash",
                 "content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn",
                 "usage":{"input_tokens":2,"output_tokens":1}}
                """));

        assertThat(model().invoke(simpleRequest(deepSeekSnapshot(Map.of("thinking", "disabled"))))
                        .content())
                .isEqualTo("ok");

        JsonNode sent = json.readTree(requestBody.get());
        assertThat(requestPath.get()).isEqualTo("/anthropic/v1/messages");
        assertThat(sent.path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(sent.has("anthropic_beta")).isFalse();
        assertThat(sent.has("disable_parallel_tool_use")).isFalse();

        assertThatThrownBy(() -> model().invoke(simpleRequest(deepSeekSnapshot("claude-sonnet-test", Map.of()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not verified");
    }

    @Test
    void closesDeepSeekToolResultAndThinkingContinuationByCorrelationId() throws Exception {
        response.set(
                Response.json(
                        200,
                        """
                {"id":"msg-ds-tool","type":"message","role":"assistant","model":"deepseek-v4-flash",
                 "content":[
                   {"type":"thinking","thinking":"private plan","signature":"opaque-signature"},
                   {"type":"tool_use","id":"toolu-ds-weather","name":"weather","input":{"city":"Paris"}}],
                 "stop_reason":"tool_use","usage":{"input_tokens":4,"output_tokens":3}}
                """));
        var tool =
                new ModelToolSpecification("weather", "1", "Weather", "schema", "1", Map.of("type", "object"), false);
        var snapshot = deepSeekSnapshot(Map.of("thinking", "enabled", "reasoning_effort", "high"));

        var first = model().invoke(request(
                snapshot, List.of(ModelMessage.text(ModelMessageRole.USER, "weather")), List.of(tool), Map.of()));

        assertThat(first.finishReason()).isEqualTo(ModelFinishReason.TOOL_CALLS);
        assertThat(first.reasoning()).isPresent();
        assertThat(first.toolCalls()).singleElement().satisfies(call -> assertThat(
                        call.providerCorrelationId().value())
                .isEqualTo("toolu-ds-weather"));

        response.set(
                Response.json(
                        200,
                        """
                {"id":"msg-ds-final","type":"message","role":"assistant","model":"deepseek-v4-flash",
                 "content":[{"type":"text","text":"sunny"}],"stop_reason":"end_turn",
                 "usage":{"input_tokens":8,"output_tokens":2}}
                """));
        var finalResponse = model().invoke(request(
                snapshot,
                List.of(
                        ModelMessage.text(ModelMessageRole.USER, "weather"),
                        ModelMessage.assistant(
                                "", first.toolCalls(), first.reasoning().orElseThrow()),
                        ModelMessage.tool(new ProviderToolCallCorrelationId("toolu-ds-weather"), "sunny")),
                List.of(tool),
                Map.of()));

        assertThat(finalResponse.content()).isEqualTo("sunny");
        JsonNode sent = json.readTree(requestBody.get());
        assertThat(sent.path("messages")
                        .get(1)
                        .path("content")
                        .get(0)
                        .path("signature")
                        .asText())
                .isEqualTo("opaque-signature");
        assertThat(sent.path("messages")
                        .get(2)
                        .path("content")
                        .get(0)
                        .path("tool_use_id")
                        .asText())
                .isEqualTo("toolu-ds-weather");
        assertThat(sent.path("output_config").path("effort").asText()).isEqualTo("high");
        assertThat(sent.path("thinking").has("budget_tokens")).isFalse();
    }

    @Test
    void rejectsForcedToolsWithThinkingAndDeepSeekIgnoredBudgetBeforeNetwork() {
        var tool = new ModelToolSpecification("lookup", "1", "Lookup", "schema", "1", Map.of("type", "object"), false);
        var snapshot = deepSeekSnapshot(
                Map.of("thinking", "enabled", "reasoning_token_budget", 2048L, "reasoning_effort", "high"));

        assertThatThrownBy(() -> model().invoke(request(
                        snapshot,
                        List.of(ModelMessage.text(ModelMessageRole.USER, "hello")),
                        List.of(tool),
                        Map.of("tool_choice", "any"))))
                .isInstanceOf(ModelInvocationException.class)
                .extracting(value -> ((ModelInvocationException) value).category())
                .isEqualTo(ModelErrorCategory.INVALID_REQUEST);
    }

    @Test
    void rejectsServerToolsAndMismatchedSseNamesFailClosed() {
        response.set(
                Response.json(
                        200,
                        """
                {"id":"msg-server-tool","type":"message","role":"assistant","model":"deepseek-v4-flash",
                 "content":[{"type":"server_tool_use","id":"srv-1","name":"web_search","input":{}}],
                 "stop_reason":"tool_use","usage":{"input_tokens":2,"output_tokens":1}}
                """));
        assertMalformed(() -> model().invoke(simpleRequest(deepSeekSnapshot(Map.of()))));

        response.set(
                Response.sse(
                        """
                event: ping
                data: {"type":"message_start","message":{"id":"bad","model":"claude-test","usage":{}}}

                """));
        assertMalformed(() -> model().invokeStreaming(
                        simpleRequest(standardSnapshot(true, Map.of())), event -> ModelStreamControl.CONTINUE));
    }

    @Test
    void mapsErrorsAndResponseLimitsWithoutLeakingBodiesOrCredentials() {
        response.set(Response.json(529, "{\"error\":{\"message\":\"test-secret private prompt\"}}"));
        assertThatThrownBy(() -> model().invoke(simpleRequest(standardSnapshot(true, Map.of()))))
                .isInstanceOf(ModelInvocationException.class)
                .satisfies(error -> {
                    ModelInvocationException failure = (ModelInvocationException) error;
                    assertThat(failure.category()).isEqualTo(ModelErrorCategory.PROVIDER_UNAVAILABLE);
                    assertThat(failure.getMessage()).doesNotContain("test-secret", "private prompt");
                });

        response.set(Response.json(200, "{\"padding\":\"" + "x".repeat(1024) + "\"}"));
        assertThatThrownBy(() -> model(512).invoke(simpleRequest(standardSnapshot(true, Map.of()))))
                .isInstanceOf(ModelInvocationException.class)
                .satisfies(error -> {
                    ModelInvocationException failure = (ModelInvocationException) error;
                    assertThat(failure.category()).isEqualTo(ModelErrorCategory.MALFORMED_RESPONSE);
                    assertThat(failure.providerCode()).isEqualTo("response_too_large");
                    assertThat(failure.getMessage()).doesNotContain("xxxx");
                });
    }

    @Test
    void mapsStandardHttpFailuresWithoutReadingSensitiveErrorMessages() {
        Map<Integer, ModelErrorCategory> expected = Map.of(
                400, ModelErrorCategory.INVALID_REQUEST,
                401, ModelErrorCategory.AUTHENTICATION_FAILED,
                403, ModelErrorCategory.PERMISSION_DENIED,
                404, ModelErrorCategory.MODEL_NOT_FOUND,
                429, ModelErrorCategory.RATE_LIMITED);

        expected.forEach((status, category) -> {
            response.set(Response.json(status, "{\"error\":{\"message\":\"test-secret prompt\"}}"));
            assertThatThrownBy(() -> model().invoke(simpleRequest(standardSnapshot(true, Map.of()))))
                    .isInstanceOf(ModelInvocationException.class)
                    .satisfies(error -> {
                        ModelInvocationException failure = (ModelInvocationException) error;
                        assertThat(failure.category()).isEqualTo(category);
                        assertThat(failure.getMessage()).doesNotContain("test-secret", "prompt");
                    });
        });
    }

    @Test
    void cancelsNamedSseAtTheConsumerBoundary() {
        response.set(
                Response.sse(
                        """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg-cancel","type":"message","role":"assistant","model":"claude-test","content":[],"stop_reason":null,"usage":{"input_tokens":1,"output_tokens":0}}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"stop"}}

                """));

        assertThatThrownBy(() -> model().invokeStreaming(
                                simpleRequest(standardSnapshot(true, Map.of())),
                                event -> event instanceof ModelStreamEvent.ContentDelta
                                        ? ModelStreamControl.CANCEL
                                        : ModelStreamControl.CONTINUE))
                .isInstanceOf(ModelInvocationException.class)
                .extracting(value -> ((ModelInvocationException) value).category())
                .isEqualTo(ModelErrorCategory.CANCELLED);
    }

    @Test
    void mapsHeaderTimeoutWithoutLeakingRequestData() {
        response.set(Response.delayedJson(500, 200, "{\"error\":{\"message\":\"test-secret private prompt\"}}"));
        AgentChatRequest request = request(
                standardSnapshot(true, Map.of()),
                List.of(ModelMessage.text(ModelMessageRole.USER, "private prompt")),
                List.of(),
                Map.of(),
                Duration.ofMillis(50));

        assertThatThrownBy(() -> model().invoke(request))
                .isInstanceOf(ModelInvocationException.class)
                .satisfies(error -> {
                    ModelInvocationException failure = (ModelInvocationException) error;
                    assertThat(failure.category()).isEqualTo(ModelErrorCategory.TIMEOUT);
                    assertThat(failure.getMessage()).doesNotContain("test-secret", "private prompt");
                });
    }

    private void assertMalformed(org.assertj.core.api.ThrowableAssert.ThrowingCallable invocation) {
        assertThatThrownBy(invocation)
                .isInstanceOf(ModelInvocationException.class)
                .extracting(value -> ((ModelInvocationException) value).category())
                .isEqualTo(ModelErrorCategory.MALFORMED_RESPONSE);
    }

    private AnthropicMessagesModel model() {
        return model(1024 * 1024);
    }

    private AnthropicMessagesModel model(int maxResponseBytes) {
        return new AnthropicMessagesModel(
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
        return request(snapshot, messages, tools, options, Duration.ofSeconds(5));
    }

    private AgentChatRequest request(
            ResolvedModelSnapshot snapshot,
            List<ModelMessage> messages,
            List<ModelToolSpecification> tools,
            Map<String, Object> options,
            Duration timeout) {
        return new AgentChatRequest(
                new ModelCallId("call-1"),
                new AgentRunId("run-1"),
                1,
                1,
                snapshot,
                messages,
                tools,
                1024,
                timeout,
                options);
    }

    private ResolvedModelSnapshot standardSnapshot(boolean nativeStreaming, Map<String, Object> invocationOptions) {
        return snapshot("claude-test", AnthropicMessagesDialects.STANDARD, nativeStreaming, "/", invocationOptions);
    }

    private ResolvedModelSnapshot deepSeekSnapshot(Map<String, Object> invocationOptions) {
        return deepSeekSnapshot("deepseek-v4-flash", invocationOptions);
    }

    private ResolvedModelSnapshot deepSeekSnapshot(String providerModelId, Map<String, Object> invocationOptions) {
        return snapshot(providerModelId, AnthropicMessagesDialects.DEEPSEEK, true, "/anthropic", invocationOptions);
    }

    private ResolvedModelSnapshot snapshot(
            String providerModelId,
            String dialect,
            boolean nativeStreaming,
            String path,
            Map<String, Object> invocationOptions) {
        return ResolvedModelSnapshot.create(
                new ModelProviderId("stub"),
                "provider-v1",
                new ModelDefinitionId("model"),
                "model-v1",
                providerModelId,
                AnthropicMessagesModel.ADAPTER_TYPE,
                AnthropicMessagesModel.ADAPTER_VERSION,
                ModelApiStyles.ANTHROPIC_MESSAGES,
                dialect,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path),
                new CredentialRef("env://TEST_KEY"),
                nativeStreaming,
                EnumSet.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING, ModelCapability.REASONING),
                1_000_000,
                384_000,
                Map.of(),
                invocationOptions);
    }

    private void handle(HttpExchange exchange) throws IOException {
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        apiKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
        version.set(exchange.getRequestHeaders().getFirst("anthropic-version"));
        requestPath.set(exchange.getRequestURI().getPath());
        Response configured = response.get();
        if (configured.delayMillis() > 0) {
            try {
                Thread.sleep(configured.delayMillis());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                exchange.close();
                return;
            }
        }
        exchange.getResponseHeaders().set("Content-Type", configured.contentType());
        byte[] body = configured.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(configured.status(), body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private record Response(int status, String contentType, String body, long delayMillis) {
        private static Response json(int status, String body) {
            return new Response(status, "application/json", body, 0);
        }

        private static Response sse(String body) {
            return new Response(200, "text/event-stream", body, 0);
        }

        private static Response delayedJson(int status, long delayMillis, String body) {
            return new Response(status, "application/json", body, delayMillis);
        }
    }
}
