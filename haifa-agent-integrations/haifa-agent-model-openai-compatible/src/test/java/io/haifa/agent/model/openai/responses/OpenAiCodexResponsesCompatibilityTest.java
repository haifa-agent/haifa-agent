package io.haifa.agent.model.openai.responses;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import io.haifa.agent.model.api.ResolvedCredential;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
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

    private OpenAiResponsesModel model(String accessToken) {
        return new OpenAiResponsesModel(
                HttpClient.newHttpClient(), json, ignored -> new ResolvedCredential(accessToken), true, 1024 * 1024);
    }

    private AgentChatRequest request(ResolvedModelSnapshot snapshot) {
        return new AgentChatRequest(
                new ModelCallId("call-codex"),
                new AgentRunId("run-codex"),
                1,
                1,
                snapshot,
                java.util.List.of(ModelMessage.text(ModelMessageRole.USER, "hello")),
                java.util.List.of(),
                1024,
                Duration.ofSeconds(5),
                Map.of());
    }

    private ResolvedModelSnapshot snapshot(URI endpoint) {
        return ResolvedModelSnapshot.create(
                new ModelProviderId("openai-codex"),
                "provider-v1",
                new ModelDefinitionId("codex-test"),
                "model-v1",
                "gpt-codex-test",
                OpenAiResponsesModel.ADAPTER_TYPE,
                OpenAiResponsesModel.ADAPTER_VERSION,
                new ApiStyleId("openai-responses"),
                OpenAiResponsesDialects.OPENAI_CODEX,
                endpoint,
                new CredentialRef("model-auth://openai-codex/default"),
                false,
                Set.of(ModelCapability.TEXT_CHAT),
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
        exchange.getRequestBody().readAllBytes();
        Response configured = response.get();
        exchange.getResponseHeaders().set("Content-Type", configured.contentType());
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
