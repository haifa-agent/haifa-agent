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
        var assistant = ModelMessage.assistant(
                first.content(), first.toolCalls(), first.reasoning().orElseThrow());
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

    private GeminiGenerateContentModel model(boolean allowLoopback) {
        return new GeminiGenerateContentModel(
                HttpClient.newHttpClient(),
                json,
                ignored -> new ResolvedCredential("secret-value"),
                allowLoopback,
                1024 * 1024);
    }

    private GeminiGenerateContentModel standardStubModel() {
        return new GeminiGenerateContentModel(
                HttpClient.newHttpClient(),
                json,
                ignored -> new ResolvedCredential("secret-value"),
                false,
                1024 * 1024,
                true);
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
        return snapshot(
                GeminiDialects.STANDARD,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta"),
                "env://GEMINI_API_KEY");
    }

    private ResolvedModelSnapshot dialectSnapshot() {
        return snapshot(
                GeminiDialects.CLIPROXYAPI_ANTIGRAVITY,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta"),
                GeminiGenerateContentModel.CLIPROXY_CREDENTIAL_REF);
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
