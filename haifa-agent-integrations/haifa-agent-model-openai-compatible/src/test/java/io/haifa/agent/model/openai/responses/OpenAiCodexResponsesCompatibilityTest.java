package io.haifa.agent.model.openai.responses;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.ApiStyleId;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelCallId;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelErrorCategory;
import io.haifa.agent.model.api.ModelInvocationException;
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelStreamControl;
import io.haifa.agent.model.api.ModelToolSpecification;
import io.haifa.agent.model.api.ResolvedCredential;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenAiCodexResponsesCompatibilityTest {
    private static final String ACCOUNT_ID = "account_test-1";

    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<Response> response = new AtomicReference<>();
    private final AtomicReference<Map<String, java.util.List<String>>> headers = new AtomicReference<>();
    private final AtomicReference<JsonNode> requestBody = new AtomicReference<>();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/backend-api/codex/responses", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sendsCodexOAuthAccountAndApprovedClientHeaders() {
        response.set(
                Response.json(
                        200,
                        """
                {"id":"resp-1","status":"completed","model":"gpt-codex-test",
                 "output":[{"id":"msg-1","type":"message","role":"assistant","status":"completed",
                   "content":[{"type":"output_text","text":"ready","annotations":[]}]}],
                 "usage":{"input_tokens":1,"output_tokens":1}}
                """));

        var actual = model(accessToken()).invoke(request(snapshot(loopbackEndpoint())));

        assertThat(actual.content()).isEqualTo("ready");
        assertThat(firstHeader("Authorization")).isEqualTo("Bearer " + accessToken());
        assertThat(firstHeader("chatgpt-account-id")).isEqualTo(ACCOUNT_ID);
        assertThat(firstHeader("originator")).isEqualTo("haifa-local-test");
        assertThat(firstHeader("User-Agent")).isEqualTo("haifa-agent-test/1");
        assertThat(requestBody.get().has("max_output_tokens")).isFalse();
    }

    @Test
    void acceptsMissingContentTypeFromTheAllowlistedCodexStream() {
        response.set(
                new Response(
                        200,
                        null,
                        """
                data: {"type":"response.created","response":{"id":"resp-stream","status":"in_progress"}}

                data: {"type":"response.output_text.delta","item_id":"msg-1","output_index":0,"content_index":0,"delta":"hello world"}

                data: {"type":"response.output_text.done","item_id":"msg-1","output_index":0,"content_index":0,"text":"hello world"}

                data: {"type":"response.completed","response":{"id":"resp-stream","status":"completed","model":"gpt-codex-test","output":[],"usage":{"input_tokens":2,"output_tokens":2}}}

                """));

        var actual = model(accessToken())
                .invokeStreaming(request(snapshot(loopbackEndpoint(), true)), ignored -> ModelStreamControl.CONTINUE);

        assertThat(actual.content()).isEqualTo("hello world");
    }

    @Test
    void rebuildsToolCallsFromCodexDeltasWhenTerminalOutputIsEmpty() {
        response.set(
                new Response(
                        200,
                        null,
                        """
                data: {"type":"response.created","response":{"id":"resp-tool","status":"in_progress"}}

                data: {"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","call_id":"call-1","name":"lookup","arguments":""}}

                data: {"type":"response.function_call_arguments.delta","output_index":0,"delta":"{\\\"value\\\":"}

                data: {"type":"response.function_call_arguments.done","output_index":0,"arguments":"{\\\"value\\\":\\\"ready\\\"}"}

                data: {"type":"response.completed","response":{"id":"resp-tool","status":"completed","model":"gpt-codex-test","output":[],"usage":{"input_tokens":2,"output_tokens":2}}}

                """));
        var tool = new ModelToolSpecification("lookup", "1", "Lookup", "schema", "1", Map.of("type", "object"), false);

        var actual = model(accessToken())
                .invokeStreaming(
                        request(snapshot(loopbackEndpoint(), true), List.of(tool)),
                        ignored -> ModelStreamControl.CONTINUE);

        assertThat(actual.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo("lookup");
            assertThat(call.arguments()).containsEntry("value", "ready");
        });
    }

    @Test
    void rejectsCodexEndpointsOutsideTheExactRemoteHostAndPath() {
        assertThatThrownBy(() ->
                        OpenAiResponsesDialects.resolve(snapshot(URI.create("https://chatgpt.com/backend-api")), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Codex Responses endpoint");
        assertThatThrownBy(() -> OpenAiResponsesDialects.resolve(
                        snapshot(URI.create("https://example.com/backend-api/codex")), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Codex Responses endpoint");
    }

    @Test
    void rejectsAccessTokenWithoutTheCodexAccountClaimBeforeNetworkUse() {
        assertThatThrownBy(() -> model(jwt(Map.of("sub", "user"))).invoke(request(snapshot(loopbackEndpoint()))))
                .isInstanceOfSatisfying(ModelInvocationException.class, failure -> {
                    assertThat(failure.category()).isEqualTo(ModelErrorCategory.AUTHENTICATION_FAILED);
                    assertThat(failure.providerCode()).isEqualTo("codex_account_identity_invalid");
                    assertThat(failure.getMessage()).doesNotContain("user");
                });
        assertThat(headers.get()).isNull();
    }

    @Test
    void mapsCodexUsageLimitCodePlanAndResetWithoutExposingRawBody() {
        long resetAt = java.time.Instant.now().plusSeconds(120).getEpochSecond();
        response.set(Response.json(
                429,
                """
                {"error":{"code":"usage_limit_reached","message":"secret provider detail",
                  "plan_type":"plus","resets_at":%d}}
                """
                        .formatted(resetAt)));

        assertThatThrownBy(() -> model(accessToken()).invoke(request(snapshot(loopbackEndpoint()))))
                .isInstanceOfSatisfying(ModelInvocationException.class, failure -> {
                    assertThat(failure.category()).isEqualTo(ModelErrorCategory.RATE_LIMITED);
                    assertThat(failure.providerCode()).isEqualTo("usage_limit_reached");
                    assertThat(failure.retryable()).isFalse();
                    assertThat(failure.retryAfter()).isPresent();
                    assertThat(failure.getMessage()).contains("plus plan").doesNotContain("secret provider detail");
                });
    }

    @Test
    void projectsOnlySafeCodexErrorCodeAndParameter() {
        response.set(
                Response.json(
                        400,
                        """
                {"error":{"type":"invalid_request_error","param":"max_output_tokens",
                  "message":"secret provider detail"}}
                """));

        assertThatThrownBy(() -> model(accessToken()).invoke(request(snapshot(loopbackEndpoint()))))
                .isInstanceOfSatisfying(ModelInvocationException.class, failure -> {
                    assertThat(failure.providerCode()).isEqualTo("invalid_request_error:max_output_tokens");
                    assertThat(failure.getMessage()).doesNotContain("secret provider detail");
                });
    }

    private OpenAiResponsesModel model(String accessToken) {
        return new OpenAiResponsesModel(
                HttpClient.newHttpClient(), json, ignored -> new ResolvedCredential(accessToken), true, 1024 * 1024);
    }

    private AgentChatRequest request(ResolvedModelSnapshot snapshot) {
        return request(snapshot, List.of());
    }

    private AgentChatRequest request(ResolvedModelSnapshot snapshot, List<ModelToolSpecification> tools) {
        return new AgentChatRequest(
                new ModelCallId("call-codex"),
                new AgentRunId("run-codex"),
                1,
                1,
                snapshot,
                java.util.List.of(ModelMessage.text(ModelMessageRole.USER, "hello")),
                tools,
                1024,
                Duration.ofSeconds(5),
                Map.of());
    }

    @Test
    void rejectsUnverifiedCodexModelsBeforeNetworkUse() {
        ResolvedModelSnapshot unverified = ResolvedModelSnapshot.create(
                new ModelProviderId("openai-codex"),
                "provider-v1",
                new ModelDefinitionId("codex-test"),
                "model-v1",
                "future-codex",
                OpenAiResponsesModel.ADAPTER_TYPE,
                OpenAiResponsesModel.ADAPTER_VERSION,
                new ApiStyleId("openai-responses"),
                OpenAiResponsesDialects.OPENAI_CODEX,
                loopbackEndpoint(),
                new CredentialRef("model-auth://openai-codex/default"),
                false,
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                128_000,
                8_192,
                Map.of(
                        OpenAiResponsesDialects.CODEX_ORIGINATOR_OPTION,
                        "haifa-local-test",
                        OpenAiResponsesDialects.CODEX_USER_AGENT_OPTION,
                        "haifa-agent-test/1"),
                Map.of());

        assertThatThrownBy(() -> OpenAiResponsesDialects.resolve(unverified, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OpenAI Codex Responses model profile is not verified");
    }

    private ResolvedModelSnapshot snapshot(URI endpoint) {
        return snapshot(endpoint, false);
    }

    private ResolvedModelSnapshot snapshot(URI endpoint, boolean nativeStreaming) {
        return ResolvedModelSnapshot.create(
                new ModelProviderId("openai-codex"),
                "provider-v1",
                new ModelDefinitionId("codex-test"),
                "model-v1",
                "gpt-5.6-sol",
                OpenAiResponsesModel.ADAPTER_TYPE,
                OpenAiResponsesModel.ADAPTER_VERSION,
                new ApiStyleId("openai-responses"),
                OpenAiResponsesDialects.OPENAI_CODEX,
                endpoint,
                new CredentialRef("model-auth://openai-codex/default"),
                nativeStreaming,
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                128_000,
                8_192,
                Map.of(
                        OpenAiResponsesDialects.CODEX_ORIGINATOR_OPTION,
                        "haifa-local-test",
                        OpenAiResponsesDialects.CODEX_USER_AGENT_OPTION,
                        "haifa-agent-test/1"),
                Map.of());
    }

    private URI loopbackEndpoint() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/backend-api/codex");
    }

    private String firstHeader(String name) {
        return headers.get().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow()
                .getValue()
                .getFirst();
    }

    private String accessToken() {
        return jwt(Map.of("https://api.openai.com/auth", Map.of("chatgpt_account_id", ACCOUNT_ID)));
    }

    private String jwt(Map<String, Object> payload) {
        try {
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8))
                    + "."
                    + encoder.encodeToString(json.writeValueAsBytes(payload))
                    + ".signature";
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        headers.set(exchange.getRequestHeaders());
        requestBody.set(json.readTree(exchange.getRequestBody().readAllBytes()));
        Response configured = response.get();
        if (configured.contentType() != null) {
            exchange.getResponseHeaders().set("Content-Type", configured.contentType());
        }
        byte[] body = configured.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(configured.status(), body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private record Response(int status, String contentType, String body) {
        private static Response json(int status, String body) {
            return new Response(status, "application/json", body);
        }
    }
}
