package io.haifa.agent.model.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AudioDataPart;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ImageDataPart;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCallId;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelErrorCategory;
import io.haifa.agent.model.api.ModelInvocationException;
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelStreamControl;
import io.haifa.agent.model.api.ModelStreamEvent;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelToolSpecification;
import io.haifa.agent.model.api.ResolvedCredential;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GeminiGenerateContentModelTest {
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void mapsOfficialTextRequestAndUsesOnlyGoogleApiKeyHeader() throws Exception {
        AtomicReference<HttpExchange> exchange = new AtomicReference<>();
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        start(exchange, requestBody, List.of(Response.json(200, textResponse("hello"))));
        AgentChatRequest request = request(
                standardSnapshot(),
                List.of(
                        ModelMessage.text(ModelMessageRole.SYSTEM, "be concise"),
                        ModelMessage.text(ModelMessageRole.USER, "hi")),
                List.of());

        var response = standardStubModel().invoke(request);

        assertThat(response.content()).isEqualTo("hello");
        assertThat(exchange.get().getRequestURI().toString()).isEqualTo("/v1beta/models/gemini-test:generateContent");
        assertThat(exchange.get().getRequestHeaders().getFirst("x-goog-api-key"))
                .isEqualTo("secret-value");
        assertThat(exchange.get().getRequestHeaders().getFirst("Authorization")).isNull();
        assertThat(requestBody
                        .get()
                        .path("systemInstruction")
                        .path("parts")
                        .get(0)
                        .path("text")
                        .asText())
                .isEqualTo("be concise");
        assertThat(requestBody.get().has("tools")).isFalse();
        assertThat(requestBody.get().has("toolConfig")).isFalse();
    }

    @Test
    void dialectSendsBearerExactToolAllowlistAndExplicitSafety() throws Exception {
        AtomicReference<HttpExchange> exchange = new AtomicReference<>();
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        start(exchange, requestBody, List.of(Response.json(200, toolResponse(true))));

        var response = model(true)
                .invoke(request(
                        dialectSnapshot(),
                        List.of(ModelMessage.text(ModelMessageRole.USER, "use tool")),
                        List.of(tool("get_alpha"))));

        assertThat(exchange.get().getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer secret-value");
        assertThat(exchange.get().getRequestHeaders().getFirst("x-goog-api-key"))
                .isNull();
        assertThat(requestBody
                        .get()
                        .path("toolConfig")
                        .path("functionCallingConfig")
                        .path("allowedFunctionNames")
                        .get(0)
                        .asText())
                .isEqualTo("get_alpha");
        assertThat(requestBody.get().path("safetySettings")).hasSize(5);
        assertThat(response.toolCalls()).hasSize(1);
        assertThat(response.reasoning()).isPresent();
        assertThat(response.reasoning().orElseThrow().toString()).doesNotContain("signature-secret");
    }

    @Test
    void antigravityDirectWrapsPayloadAndExtractsProject() throws Exception {
        AtomicReference<HttpExchange> exchange = new AtomicReference<>();
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        start(exchange, requestBody, List.of(Response.json(200, directResponse(textResponse("direct-ok")))));

        var response = standardStubModel()
                .invoke(request(
                        antigravityDirectSnapshot(),
                        List.of(ModelMessage.text(ModelMessageRole.USER, "hello direct")),
                        List.of()));

        assertThat(response.content()).isEqualTo("direct-ok");
        assertThat(exchange.get().getRequestURI().toString()).isEqualTo("/v1internal:generateContent");
        assertThat(exchange.get().getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer secret-value");
        assertThat(exchange.get().getRequestHeaders().getFirst("User-Agent")).isEqualTo("Antigravity");

        JsonNode root = requestBody.get();
        assertThat(root.path("project").asText()).isEqualTo("my-custom-project");
        assertThat(root.path("model").asText()).isEqualTo("gemini-test");
        assertThat(root.path("userAgent").asText()).isEqualTo("antigravity");
        assertThat(root.path("requestType").asText()).isEqualTo("agent");
        assertThat(root.path("requestId").asText()).startsWith("agent-");
        assertThat(root.has("metadata")).isFalse();

        JsonNode inner = root.path("request");
        assertThat(inner.path("sessionId").asText()).matches("-[0-9]+");
        assertThat(inner.path("generationConfig").has("maxOutputTokens")).isFalse();
        assertThat(inner.path("contents")
                        .get(0)
                        .path("parts")
                        .get(0)
                        .path("text")
                        .asText())
                .isEqualTo("hello direct");
    }

    @Test
    void antigravityDirect429QuotaExhaustedFailsClosedAsNonRetryable() throws Exception {
        String quotaError =
                """
                {
                    "error": {
                        "code": 429,
                        "message": "Resource has been exhausted",
                        "status": "RESOURCE_EXHAUSTED",
                        "details": [
                            {
                                "@type": "type.googleapis.com/google.rpc.ErrorInfo",
                                "reason": "QUOTA_EXHAUSTED",
                                "domain": "googleapis.com"
                            }
                        ]
                    }
                }
                """;
        start(new AtomicReference<>(), new AtomicReference<>(), List.of(Response.json(429, quotaError)));

        assertThatThrownBy(() -> standardStubModel()
                        .invoke(request(
                                antigravityDirectSnapshot(),
                                List.of(ModelMessage.text(ModelMessageRole.USER, "hi")),
                                List.of())))
                .isInstanceOfSatisfying(ModelInvocationException.class, failure -> {
                    assertThat(failure.category()).isEqualTo(ModelErrorCategory.RATE_LIMITED);
                    assertThat(failure.retryable()).isFalse();
                    assertThat(failure.providerCode()).isEqualTo("quota_exhausted");
                });
    }

    @Test
    void antigravityDirect429InsufficientCreditsFailsClosedAsNonRetryable() throws Exception {
        String creditError =
                """
                {
                    "error": {
                        "code": 429,
                        "message": "Insufficient G1 credits",
                        "status": "RESOURCE_EXHAUSTED",
                        "details": [
                            {
                                "@type": "type.googleapis.com/google.rpc.ErrorInfo",
                                "reason": "INSUFFICIENT_G1_CREDITS_BALANCE",
                                "domain": "googleapis.com"
                            }
                        ]
                    }
                }
                """;
        start(new AtomicReference<>(), new AtomicReference<>(), List.of(Response.json(429, creditError)));

        assertThatThrownBy(() -> standardStubModel()
                        .invoke(request(
                                antigravityDirectSnapshot(),
                                List.of(ModelMessage.text(ModelMessageRole.USER, "hi")),
                                List.of())))
                .isInstanceOfSatisfying(ModelInvocationException.class, failure -> {
                    assertThat(failure.category()).isEqualTo(ModelErrorCategory.RATE_LIMITED);
                    assertThat(failure.retryable()).isFalse();
                    assertThat(failure.providerCode()).isEqualTo("insufficient_g1_credits_balance");
                });
    }

    @Test
    void mapsUploadedImageAndAudioAsNativeGeminiInlineData() throws Exception {
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        start(new AtomicReference<>(), requestBody, List.of(Response.json(200, textResponse("media-ok"))));
        byte[] image = new byte[] {1, 2, 3};
        byte[] audio = new byte[] {4, 5, 6, 7};

        var response = model(true)
                .invoke(request(
                        dialectSnapshot(),
                        List.of(ModelMessage.user(
                                "inspect media",
                                List.of(new ImageDataPart("image/png", image)),
                                List.of(new AudioDataPart("audio/wav", audio)))),
                        List.of()));

        assertThat(response.content()).isEqualTo("media-ok");
        JsonNode parts = requestBody.get().path("contents").get(0).path("parts");
        assertThat(parts).hasSize(3);
        assertThat(parts.get(0).path("text").asText()).isEqualTo("inspect media");
        assertThat(parts.get(1).path("inlineData").path("mimeType").asText()).isEqualTo("image/png");
        assertThat(Base64.getDecoder()
                        .decode(parts.get(1).path("inlineData").path("data").asText()))
                .containsExactly(image);
        assertThat(parts.get(2).path("inlineData").path("mimeType").asText()).isEqualTo("audio/wav");
        assertThat(Base64.getDecoder()
                        .decode(parts.get(2).path("inlineData").path("data").asText()))
                .containsExactly(audio);
    }

    @Test
    void replaysProtectedSignatureAndCorrelatesFunctionResponse() throws Exception {
        AtomicReference<HttpExchange> exchange = new AtomicReference<>();
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        Queue<Response> responses = new ArrayDeque<>();
        responses.add(Response.json(200, toolResponse(true)));
        responses.add(Response.json(200, textResponse("done")));
        start(exchange, requestBody, responses);
        var model = model(true);
        var first = model.invoke(request(
                dialectSnapshot(),
                List.of(ModelMessage.text(ModelMessageRole.USER, "use tool")),
                List.of(tool("get_alpha"))));
        var firstCall = first.toolCalls().getFirst();
        var normalizedCall =
                new ModelToolCall(firstCall.providerCorrelationId(), firstCall.name(), Map.of("value", 1L));
        var assistant = ModelMessage.assistant(
                first.content(), List.of(normalizedCall), first.reasoning().orElseThrow());
        var toolResult = ModelMessage.tool(
                first.toolCalls().getFirst().providerCorrelationId(), "ok", Map.of("value", 7), false);

        var second = model.invoke(request(
                dialectSnapshot(),
                List.of(ModelMessage.text(ModelMessageRole.USER, "use tool"), assistant, toolResult),
                List.of(tool("get_alpha"))));

        assertThat(second.content()).isEqualTo("done");
        JsonNode modelPart =
                requestBody.get().path("contents").get(1).path("parts").get(0);
        assertThat(modelPart.path("thoughtSignature").asText()).isEqualTo("signature-secret");
        JsonNode functionResponse =
                requestBody.get().path("contents").get(2).path("parts").get(0).path("functionResponse");
        assertThat(functionResponse.path("id").asText()).isEqualTo("call-1");
        assertThat(functionResponse.path("name").asText()).isEqualTo("get_alpha");
    }

    @Test
    void missingSignatureFailsClosed() throws Exception {
        start(new AtomicReference<>(), new AtomicReference<>(), List.of(Response.json(200, toolResponse(false))));

        assertThatThrownBy(() -> model(true)
                        .invoke(request(
                                dialectSnapshot(),
                                List.of(ModelMessage.text(ModelMessageRole.USER, "use tool")),
                                List.of(tool("get_alpha")))))
                .isInstanceOfSatisfying(ModelInvocationException.class, failure -> {
                    assertThat(failure.category()).isEqualTo(ModelErrorCategory.MALFORMED_RESPONSE);
                    assertThat(failure.providerCode()).isEqualTo("invalid_function_call");
                });
    }

    @Test
    void emptySuccessFailsClosed() throws Exception {
        start(new AtomicReference<>(), new AtomicReference<>(), List.of(Response.json(200, "")));
        assertThatThrownBy(() -> model(true)
                        .invoke(request(
                                dialectSnapshot(), List.of(ModelMessage.text(ModelMessageRole.USER, "hi")), List.of())))
                .isInstanceOfSatisfying(ModelInvocationException.class, failure -> assertThat(failure.category())
                        .isEqualTo(ModelErrorCategory.MALFORMED_RESPONSE));
    }

    @Test
    void leadingModelToolCallWithoutUserAnchorFailsPreDispatch() {
        var assistant = ModelMessage.assistant(
                "",
                List.of(new ModelToolCall(
                        new io.haifa.agent.core.tool.ProviderToolCallCorrelationId("call-1"), "get_alpha", Map.of())),
                io.haifa.agent.model.api.SensitiveModelReasoning.of(
                        "{\"version\":1,\"parts\":[{\"functionCall\":{\"id\":\"call-1\",\"name\":\"get_alpha\",\"args\":{}},\"thoughtSignature\":\"sig\"}]}"));
        var toolResult = ModelMessage.tool(new io.haifa.agent.core.tool.ProviderToolCallCorrelationId("call-1"), "ok");

        assertThatThrownBy(() -> standardStubModel()
                        .invoke(request(
                                standardSnapshot(),
                                List.of(ModelMessage.text(ModelMessageRole.SYSTEM, "sys"), assistant, toolResult),
                                List.of(tool("get_alpha")))))
                .isInstanceOfSatisfying(ModelInvocationException.class, failure -> {
                    assertThat(failure.category()).isEqualTo(ModelErrorCategory.INVALID_REQUEST);
                    assertThat(failure.providerCode()).isEqualTo("gemini_turn_anchor_missing");
                });
    }

    @Test
    void leadingOrphanFunctionResponseWithoutUserAnchorFailsPreDispatch() {
        var toolResult = ModelMessage.tool(new io.haifa.agent.core.tool.ProviderToolCallCorrelationId("call-1"), "ok");

        assertThatThrownBy(() -> standardStubModel()
                        .invoke(request(
                                standardSnapshot(),
                                List.of(ModelMessage.text(ModelMessageRole.SYSTEM, "sys"), toolResult),
                                List.of(tool("get_alpha")))))
                .isInstanceOfSatisfying(ModelInvocationException.class, failure -> {
                    assertThat(failure.category()).isEqualTo(ModelErrorCategory.INVALID_REQUEST);
                    assertThat(failure.providerCode()).isEqualTo("gemini_tool_call_unmatched");
                });
    }

    @Test
    void emptyContentsFailsPreDispatch() {
        assertThatThrownBy(() -> standardStubModel()
                        .invoke(request(
                                standardSnapshot(),
                                List.of(ModelMessage.text(ModelMessageRole.SYSTEM, "only system")),
                                List.of())))
                .isInstanceOfSatisfying(ModelInvocationException.class, failure -> {
                    assertThat(failure.category()).isEqualTo(ModelErrorCategory.INVALID_REQUEST);
                    assertThat(failure.providerCode()).isEqualTo("gemini_contents_empty");
                });
    }

    @Test
    void trailingFunctionCallWithoutResponseFailsPreDispatch() {
        var assistantCall = ModelMessage.assistant(
                "",
                List.of(new ModelToolCall(
                        new io.haifa.agent.core.tool.ProviderToolCallCorrelationId("call-1"),
                        "get_alpha",
                        Map.of("step", 1))),
                io.haifa.agent.model.api.SensitiveModelReasoning.of(
                        "{\"version\":1,\"parts\":[{\"functionCall\":{\"id\":\"call-1\",\"name\":\"get_alpha\",\"args\":{\"step\":1}},\"thoughtSignature\":\"sig-1\"}]}"));

        var messages = List.of(ModelMessage.text(ModelMessageRole.USER, "execute step"), assistantCall);

        assertThatThrownBy(() ->
                        standardStubModel().invoke(request(standardSnapshot(), messages, List.of(tool("get_alpha")))))
                .isInstanceOfSatisfying(ModelInvocationException.class, failure -> {
                    assertThat(failure.category()).isEqualTo(ModelErrorCategory.INVALID_REQUEST);
                    assertThat(failure.providerCode()).isEqualTo("gemini_tool_call_unmatched");
                });
    }

    @Test
    void orphanFunctionResponseAfterUserAnchorFailsPreDispatch() {
        var orphanToolResult =
                ModelMessage.tool(new io.haifa.agent.core.tool.ProviderToolCallCorrelationId("orphan-call"), "result");

        var messages = List.of(ModelMessage.text(ModelMessageRole.USER, "execute step"), orphanToolResult);

        assertThatThrownBy(() ->
                        standardStubModel().invoke(request(standardSnapshot(), messages, List.of(tool("get_alpha")))))
                .isInstanceOfSatisfying(ModelInvocationException.class, failure -> {
                    assertThat(failure.category()).isEqualTo(ModelErrorCategory.INVALID_REQUEST);
                    assertThat(failure.providerCode()).isEqualTo("gemini_tool_call_unmatched");
                });
    }

    @Test
    void sequentialFunctionCallingRequestPreservesUserTurnAnchorAndThoughtSignatures() throws Exception {
        AtomicReference<HttpExchange> exchange = new AtomicReference<>();
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        start(exchange, requestBody, List.of(Response.json(200, textResponse("all done"))));

        var assistant1 = ModelMessage.assistant(
                "",
                List.of(new ModelToolCall(
                        new io.haifa.agent.core.tool.ProviderToolCallCorrelationId("call-1"),
                        "get_alpha",
                        Map.of("step", 1))),
                io.haifa.agent.model.api.SensitiveModelReasoning.of(
                        "{\"version\":1,\"parts\":[{\"functionCall\":{\"id\":\"call-1\",\"name\":\"get_alpha\",\"args\":{\"step\":1}},\"thoughtSignature\":\"sig-1\"}]}"));
        var toolResult1 = ModelMessage.tool(
                new io.haifa.agent.core.tool.ProviderToolCallCorrelationId("call-1"),
                "result 1",
                Map.of("data", "v1"),
                false);

        var assistant2 = ModelMessage.assistant(
                "",
                List.of(new ModelToolCall(
                        new io.haifa.agent.core.tool.ProviderToolCallCorrelationId("call-2"),
                        "get_alpha",
                        Map.of("step", 2))),
                io.haifa.agent.model.api.SensitiveModelReasoning.of(
                        "{\"version\":1,\"parts\":[{\"functionCall\":{\"id\":\"call-2\",\"name\":\"get_alpha\",\"args\":{\"step\":2}},\"thoughtSignature\":\"sig-2\"}]}"));
        var toolResult2 = ModelMessage.tool(
                new io.haifa.agent.core.tool.ProviderToolCallCorrelationId("call-2"),
                "result 2",
                Map.of("data", "v2"),
                false);

        var messages = List.of(
                ModelMessage.text(ModelMessageRole.SYSTEM, "conversation summary facts"),
                ModelMessage.text(ModelMessageRole.USER, "execute sequential steps"),
                assistant1,
                toolResult1,
                assistant2,
                toolResult2);

        var response = standardStubModel().invoke(request(standardSnapshot(), messages, List.of(tool("get_alpha"))));

        assertThat(response.content()).isEqualTo("all done");

        JsonNode body = requestBody.get();
        assertThat(body.path("systemInstruction")
                        .path("parts")
                        .get(0)
                        .path("text")
                        .asText())
                .isEqualTo("conversation summary facts");

        JsonNode contents = body.path("contents");
        assertThat(contents).hasSize(5);

        // contents[0] = User turn anchor
        assertThat(contents.get(0).path("role").asText()).isEqualTo("user");
        assertThat(contents.get(0).path("parts").get(0).path("text").asText()).isEqualTo("execute sequential steps");

        // contents[1] = Model functionCall 1 + signature
        assertThat(contents.get(1).path("role").asText()).isEqualTo("model");
        assertThat(contents.get(1)
                        .path("parts")
                        .get(0)
                        .path("functionCall")
                        .path("id")
                        .asText())
                .isEqualTo("call-1");
        assertThat(contents.get(1).path("parts").get(0).path("thoughtSignature").asText())
                .isEqualTo("sig-1");

        // contents[2] = User functionResponse 1
        assertThat(contents.get(2).path("role").asText()).isEqualTo("user");
        assertThat(contents.get(2)
                        .path("parts")
                        .get(0)
                        .path("functionResponse")
                        .path("id")
                        .asText())
                .isEqualTo("call-1");

        // contents[3] = Model functionCall 2 + signature
        assertThat(contents.get(3).path("role").asText()).isEqualTo("model");
        assertThat(contents.get(3)
                        .path("parts")
                        .get(0)
                        .path("functionCall")
                        .path("id")
                        .asText())
                .isEqualTo("call-2");
        assertThat(contents.get(3).path("parts").get(0).path("thoughtSignature").asText())
                .isEqualTo("sig-2");

        // contents[4] = User functionResponse 2
        assertThat(contents.get(4).path("role").asText()).isEqualTo("user");
        assertThat(contents.get(4)
                        .path("parts")
                        .get(0)
                        .path("functionResponse")
                        .path("id")
                        .asText())
                .isEqualTo("call-2");
    }

    @Test
    void rejectsUnapprovedDialectCredentialReferenceBeforeNetwork() {
        ResolvedModelSnapshot invalid = snapshot(
                GeminiDialects.CLIPROXYAPI_ANTIGRAVITY, URI.create("http://127.0.0.1:8317/v1beta"), "env://OTHER");
        assertThatThrownBy(() -> model(true)
                        .invoke(request(invalid, List.of(ModelMessage.text(ModelMessageRole.USER, "hi")), List.of())))
                .isInstanceOfSatisfying(ModelInvocationException.class, failure -> assertThat(failure.providerCode())
                        .isEqualTo("invalid_cliproxy_binding"));
    }

    @Test
    void aggregatesSseAndReportsFinalUsage() throws Exception {
        String sse = "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hel\"}]}}]}\n\n"
                + "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"lo\"}]},\"finishReason\":\"STOP\"}],"
                + "\"usageMetadata\":{\"promptTokenCount\":2,\"candidatesTokenCount\":3}}\n\n";
        start(new AtomicReference<>(), new AtomicReference<>(), List.of(Response.sse(sse)));
        List<ModelStreamEvent> events = new ArrayList<>();

        var response = model(true)
                .invokeStreaming(
                        request(dialectSnapshot(), List.of(ModelMessage.text(ModelMessageRole.USER, "hi")), List.of()),
                        event -> {
                            events.add(event);
                            return ModelStreamControl.CONTINUE;
                        });

        assertThat(response.content()).isEqualTo("hello");
        assertThat(response.usage().inputTokens()).isEqualTo(2);
        assertThat(events.stream().filter(ModelStreamEvent.ContentDelta.class::isInstance))
                .hasSize(2);
    }

    @Test
    void antigravityDirectUnwrapsCloudCodeSseResponseFrames() throws Exception {
        String sse = "data: "
                + directResponse(
                        "{\"responseId\":\"direct-response-1\",\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hel\"}]}}]}")
                + "\n\n"
                + "data: "
                + directResponse(
                        "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"lo\"}]},\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":2,\"candidatesTokenCount\":3}}")
                + "\n\n";
        start(new AtomicReference<>(), new AtomicReference<>(), List.of(Response.sse(sse)));
        List<ModelStreamEvent> events = new ArrayList<>();

        var response = standardStubModel()
                .invokeStreaming(
                        request(
                                antigravityDirectSnapshot(),
                                List.of(ModelMessage.text(ModelMessageRole.USER, "hi")),
                                List.of()),
                        event -> {
                            events.add(event);
                            return ModelStreamControl.CONTINUE;
                        });

        assertThat(response.responseId()).isEqualTo("direct-response-1");
        assertThat(response.content()).isEqualTo("hello");
        assertThat(response.usage().inputTokens()).isEqualTo(2);
        assertThat(events.stream().filter(ModelStreamEvent.ContentDelta.class::isInstance))
                .hasSize(2);
    }

    private GeminiGenerateContentModel model(boolean allowLoopback) {
        return new GeminiGenerateContentModel(
                HttpClient.newHttpClient(),
                json,
                ignored -> new ResolvedCredential("secret-value"),
                allowLoopback,
                1024 * 1024);
    }

    @Test
    void directDialectRejectsRequestProjectAndRequiresTrustedCredentialReference() throws Exception {
        start(
                new AtomicReference<>(),
                new AtomicReference<>(),
                List.of(new Response(200, "application/json", textResponse("unused"))));
        var model = new GeminiGenerateContentModel(
                HttpClient.newHttpClient(),
                json,
                ignored -> new ResolvedCredential("secret-value"),
                false,
                1024 * 1024,
                true,
                ref -> java.util.Optional.empty());
        AgentChatRequest injected = new AgentChatRequest(
                new ModelCallId("call-project"),
                new AgentRunId("run-project"),
                1,
                1,
                antigravityDirectSnapshot(),
                List.of(ModelMessage.text(ModelMessageRole.USER, "hi")),
                List.of(),
                256,
                Duration.ofSeconds(5),
                Map.of("project", "request-project"));

        assertThatThrownBy(() -> model.invoke(injected))
                .isInstanceOfSatisfying(ModelInvocationException.class, failure -> {
                    assertThat(failure.providerCode()).isEqualTo("project_injection_forbidden");
                });

        AgentChatRequest missingTrustedProject = request(
                antigravityDirectSnapshot(), List.of(ModelMessage.text(ModelMessageRole.USER, "hi")), List.of());
        assertThatThrownBy(() -> model.invoke(missingTrustedProject))
                .isInstanceOfSatisfying(ModelInvocationException.class, failure -> {
                    assertThat(failure.providerCode()).isEqualTo("antigravity_project_unavailable");
                });
    }

    @Test
    void rejectsCrlfOrBlankCredentialOrProjectBeforeNetworkUse() {
        var badCredentialModel = new GeminiGenerateContentModel(
                HttpClient.newHttpClient(),
                json,
                ignored -> new ResolvedCredential("bad\r\nsecret"),
                false,
                1024 * 1024,
                true,
                ref -> java.util.Optional.of("my-project"));
        assertThatThrownBy(() -> badCredentialModel.invoke(request(
                        standardSnapshot(), List.of(ModelMessage.text(ModelMessageRole.USER, "hi")), List.of())))
                .isInstanceOfSatisfying(ModelInvocationException.class, failure -> {
                    assertThat(failure.category()).isEqualTo(ModelErrorCategory.AUTHENTICATION_FAILED);
                });

        var badProjectModel = new GeminiGenerateContentModel(
                HttpClient.newHttpClient(),
                json,
                ignored -> new ResolvedCredential("secret-value"),
                false,
                1024 * 1024,
                true,
                ref -> java.util.Optional.of("bad\r\nproject"));
        assertThatThrownBy(() -> badProjectModel.invoke(request(
                        antigravityDirectSnapshot(),
                        List.of(ModelMessage.text(ModelMessageRole.USER, "hi")),
                        List.of())))
                .isInstanceOfSatisfying(ModelInvocationException.class, failure -> {
                    assertThat(failure.category()).isEqualTo(ModelErrorCategory.AUTHENTICATION_FAILED);
                    assertThat(failure.providerCode()).isEqualTo("antigravity_project_invalid");
                });
    }

    @Test
    void directDialectAllowsOnlyGovernedProdAndDailyEndpoints() {
        assertThat(GeminiGenerateContentModel.isGovernedAntigravityDirectEndpoint(
                        URI.create("https://cloudcode-pa.googleapis.com/v1internal")))
                .isTrue();
        assertThat(GeminiGenerateContentModel.isGovernedAntigravityDirectEndpoint(
                        URI.create("https://daily-cloudcode-pa.googleapis.com/v1internal")))
                .isTrue();
        assertThat(GeminiGenerateContentModel.isGovernedAntigravityDirectEndpoint(
                        URI.create("https://daily-cloudcode-pa.sandbox.googleapis.com/v1internal")))
                .isFalse();
        assertThat(GeminiGenerateContentModel.isGovernedAntigravityDirectEndpoint(
                        URI.create("https://example.com/v1internal")))
                .isFalse();
    }

    private GeminiGenerateContentModel standardStubModel() {
        return new GeminiGenerateContentModel(
                HttpClient.newHttpClient(),
                json,
                ignored -> new ResolvedCredential("secret-value"),
                false,
                1024 * 1024,
                true,
                ref -> java.util.Optional.of("my-custom-project"));
    }

    private AgentChatRequest request(
            ResolvedModelSnapshot snapshot, List<ModelMessage> messages, List<ModelToolSpecification> tools) {
        return new AgentChatRequest(
                new ModelCallId("call-test"),
                new AgentRunId("run-test"),
                1,
                1,
                snapshot,
                messages,
                tools,
                256,
                Duration.ofSeconds(5),
                Map.of());
    }

    private ResolvedModelSnapshot standardSnapshot() {
        int port = server == null ? 8080 : server.getAddress().getPort();
        return snapshot(
                GeminiDialects.STANDARD, URI.create("http://127.0.0.1:" + port + "/v1beta"), "env://GEMINI_API_KEY");
    }

    private ResolvedModelSnapshot dialectSnapshot() {
        int port = server == null ? 8080 : server.getAddress().getPort();
        return snapshot(
                GeminiDialects.CLIPROXYAPI_ANTIGRAVITY,
                URI.create("http://127.0.0.1:" + port + "/v1beta"),
                GeminiGenerateContentModel.CLIPROXY_CREDENTIAL_REF);
    }

    private ResolvedModelSnapshot antigravityDirectSnapshot() {
        int port = server == null ? 8080 : server.getAddress().getPort();
        return ResolvedModelSnapshot.create(
                new ModelProviderId("google-antigravity"),
                "1",
                new ModelDefinitionId("gemini-2.5-flash"),
                "1",
                "gemini-test",
                ModelApiStyles.GOOGLE_GEMINI_ADAPTER,
                GeminiGenerateContentModel.ADAPTER_VERSION,
                ModelApiStyles.GOOGLE_GEMINI_GENERATE_CONTENT,
                GeminiDialects.ANTIGRAVITY_DIRECT,
                URI.create("http://127.0.0.1:" + port + "/v1internal"),
                new CredentialRef("model-auth://google-antigravity/default"),
                true,
                EnumSet.of(
                        ModelCapability.TEXT_CHAT,
                        ModelCapability.TOOL_CALLING,
                        ModelCapability.STRUCTURED_OUTPUT,
                        ModelCapability.IMAGE_UPLOAD_INPUT,
                        ModelCapability.AUDIO_INPUT,
                        ModelCapability.REASONING),
                8192,
                1024,
                Map.of(),
                Map.of());
    }

    private ResolvedModelSnapshot snapshot(String dialect, URI endpoint, String credential) {
        return ResolvedModelSnapshot.create(
                new ModelProviderId("provider"),
                "1",
                new ModelDefinitionId("model"),
                "1",
                "gemini-test",
                ModelApiStyles.GOOGLE_GEMINI_ADAPTER,
                GeminiGenerateContentModel.ADAPTER_VERSION,
                ModelApiStyles.GOOGLE_GEMINI_GENERATE_CONTENT,
                dialect,
                endpoint,
                new CredentialRef(credential),
                true,
                EnumSet.of(
                        ModelCapability.TEXT_CHAT,
                        ModelCapability.TOOL_CALLING,
                        ModelCapability.STRUCTURED_OUTPUT,
                        ModelCapability.IMAGE_UPLOAD_INPUT,
                        ModelCapability.AUDIO_INPUT,
                        ModelCapability.REASONING),
                8192,
                1024,
                Map.of(),
                Map.of());
    }

    private static ModelToolSpecification tool(String name) {
        return new ModelToolSpecification(
                name,
                "1",
                "test tool",
                "schema",
                "1",
                Map.of("type", "object", "properties", Map.of("value", Map.of("type", "integer"))),
                true);
    }

    private void start(
            AtomicReference<HttpExchange> exchange, AtomicReference<JsonNode> requestBody, List<Response> responses)
            throws IOException {
        start(exchange, requestBody, new ArrayDeque<>(responses));
    }

    private void start(
            AtomicReference<HttpExchange> exchange, AtomicReference<JsonNode> requestBody, Queue<Response> responses)
            throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", current -> {
            exchange.set(current);
            try {
                requestBody.set(json.readTree(current.getRequestBody()));
            } catch (Exception ignored) {
            }
            Response response = responses.remove();
            current.getResponseHeaders().set("Content-Type", response.contentType());
            byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
            current.sendResponseHeaders(response.status(), bytes.length);
            current.getResponseBody().write(bytes);
            current.close();
        });
        server.start();
    }

    private static String textResponse(String text) {
        return "{\"responseId\":\"response-1\",\"modelVersion\":\"gemini-test\",\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\""
                + text
                + "\"}]},\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":2,\"candidatesTokenCount\":1}}";
    }

    private static String directResponse(String response) {
        return "{\"response\":" + response + ",\"traceId\":\"trace-ignored\"}";
    }

    private static String toolResponse(boolean signature) {
        return "{\"responseId\":\"response-1\",\"modelVersion\":\"gemini-test\",\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"functionCall\":{\"id\":\"call-1\",\"name\":\"get_alpha\",\"args\":{\"value\":1}}"
                + (signature ? ",\"thoughtSignature\":\"signature-secret\"" : "")
                + "}]},\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":2,\"candidatesTokenCount\":1}}";
    }

    private record Response(int status, String contentType, String body) {
        static Response json(int status, String body) {
            return new Response(status, "application/json", body);
        }

        static Response sse(String body) {
            return new Response(200, "text/event-stream", body);
        }
    }
}
