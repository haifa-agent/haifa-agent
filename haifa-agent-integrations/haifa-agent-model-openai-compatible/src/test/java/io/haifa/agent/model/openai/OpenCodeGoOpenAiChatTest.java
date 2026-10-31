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
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelProviderDefinition;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelRequestId;
import io.haifa.agent.model.api.ModelStatus;
import io.haifa.agent.model.api.ModelStreamControl;
import io.haifa.agent.model.api.ProviderStatus;
import io.haifa.agent.model.api.ResolvedCredential;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * OpenCode Go refuses to route a request without its documented session header, and replies HTTP 400
 * {@code missingsessionid}. Both transport paths of the shared adapter must therefore carry the session header and a
 * product user agent, because the synchronous path is still reachable for a non-streaming snapshot.
 */
class OpenCodeGoOpenAiChatTest {
    private static final String RUN_ID = "01a18cfc-27fa-7ead-953d-0a9eaf6c7d24";
    private static final String SESSION_HEADER = "x-opencode-session";
    private static final String USER_AGENT = "haifa-coding-agent/opencode-go";

    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<String> responseBody = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> sessionHeader = new AtomicReference<>();
    private final AtomicReference<String> userAgentHeader = new AtomicReference<>();
    private HttpServer server;
    private ModelProviderDefinition provider;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", this::handle);
        server.start();
        provider = provider(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sendsTheDocumentedSessionHeaderOnTheStreamingPath() {
        responseBody.set(
                """
                data: {"id":"go-stream","model":"deepseek-v4.1-flash","choices":[{"index":0,"delta":{"content":"hi"},"finish_reason":"stop"}]}

                data: {"id":"go-stream","model":"deepseek-v4.1-flash","choices":[],"usage":{"prompt_tokens":3,"completion_tokens":1}}

                data: [DONE]

                """);

        var result = model().invokeStreaming(request(), ignored -> ModelStreamControl.CONTINUE);

        assertThat(result.content()).isEqualTo("hi");
        assertThat(sessionHeader.get()).isEqualTo(RUN_ID);
        assertThat(userAgentHeader.get()).isEqualTo(USER_AGENT);
    }

    @Test
    void sendsTheSameHeadersOnTheSynchronousPath() {
        responseBody.set(syncBody("done"));

        var result = model().invoke(request());

        assertThat(result.content()).isEqualTo("done");
        assertThat(sessionHeader.get()).isEqualTo(RUN_ID);
        assertThat(userAgentHeader.get()).isEqualTo(USER_AGENT);
    }

    @Test
    void sendsTheFrozenConversationAffinityRatherThanThePerRunId() {
        responseBody.set(syncBody("affinity"));

        var result = model().invoke(request("conversation-root", Map.of()));

        assertThat(result.content()).isEqualTo("affinity");
        assertThat(sessionHeader.get()).isEqualTo("conversation-root");
    }

    @Test
    void mapsTheConsoleCostAndBilledTokens() {
        responseBody.set(
                """
                {"id":"go-cost","model":"deepseek-v4.1-flash",
                 "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"done"}}],
                 "usage":{"prompt_tokens":1200,"completion_tokens":300,"cost":0.0412,
                          "opencode":{"billing_source":"go","allowance":0.02,
                                      "tokens":{"input":1200,"output":300,"reasoning":120,"cache_read":8000}}}}
                """);

        var usage = model().invoke(request()).usage();

        assertThat(usage.costKnown()).isTrue();
        assertThat(usage.costMinorUnits()).isEqualTo(4);
        assertThat(usage.cacheHitTokens()).isEqualTo(8000);
        assertThat(usage.reasoningTokens()).isEqualTo(120);
    }

    @Test
    void sendsTheThinkingContractOnlyWhenReasoningIsEnabled() {
        responseBody.set(syncBody("quiet"));
        model().invoke(request(RUN_ID, Map.of("thinking", "disabled")));
        assertThat(readRequestBody().has("thinking")).isFalse();

        responseBody.set(syncBody("thinking"));
        model().invoke(request(RUN_ID, Map.of("thinking", "enabled", "reasoning_effort", "high")));
        JsonNode sent = readRequestBody();
        assertThat(sent.path("thinking").path("type").asText()).isEqualTo("enabled");
        assertThat(sent.path("reasoning_effort").asText()).isEqualTo("high");

        responseBody.set(syncBody("rejected"));
        assertThatThrownBy(() -> OpenCodeGoOpenAiChatDialect.INSTANCE.applyRequest(
                        request(RUN_ID, Map.of("thinking", "enabled", "temperature", 0.2d)),
                        new java.util.LinkedHashMap<>(Map.of("tool_choice", "auto"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("temperature");
    }

    @Test
    void resolvesTheModelListOnTheSubscriptionRoot() {
        var dialect = OpenAiCompatibleDialects.resolve(provider);

        assertThat(dialect.modelsUri(URI.create("https://opencode.ai/inference/go/openai/v1")))
                .hasToString("https://opencode.ai/inference/go/v1/models");
        assertThat(dialect.modelsUri(URI.create("http://127.0.0.1:8080"))).hasToString("http://127.0.0.1:8080/models");
    }

    private OpenAiCompatibleChatModel model() {
        return new OpenAiCompatibleChatModel(
                provider,
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                json,
                ignored -> new ResolvedCredential("opencode-go-test-secret"),
                true,
                1024 * 1024);
    }

    private AgentChatRequest request() {
        return request(RUN_ID, Map.of());
    }

    private AgentChatRequest request(String sessionAffinity, Map<String, Object> invocationOptions) {
        ModelDefinition model = provider.models().getFirst();
        ResolvedModelSnapshot snapshot = ResolvedModelSnapshot.create(
                provider.id(),
                provider.version(),
                model.id(),
                model.version(),
                model.providerModelId(),
                ModelApiStyles.OPENAI_CHAT_ADAPTER,
                "1.0.0",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                OpenAiCompatibleDialects.OPENCODE_GO,
                provider.endpoint(),
                provider.credentialRef(),
                true,
                model.capabilities(),
                model.contextWindow(),
                model.maxOutputTokens(),
                provider.options(),
                invocationOptions);
        return new AgentChatRequest(
                new ModelCallId("opencode-go-call"),
                new ModelRequestId("opencode-go-request"),
                new AgentRunId(RUN_ID),
                sessionAffinity,
                1,
                1,
                snapshot,
                List.of(ModelMessage.text(ModelMessageRole.USER, "hello")),
                List.of(),
                1024,
                Duration.ofSeconds(5),
                Map.of(),
                Optional.empty());
    }

    private JsonNode readRequestBody() {
        try {
            return json.readTree(requestBody.get());
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String syncBody(String content) {
        return """
                {"id":"go-sync","model":"deepseek-v4.1-flash",
                 "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"%s"}}],
                 "usage":{"prompt_tokens":3,"completion_tokens":1}}
                """
                .formatted(content);
    }

    private ModelProviderDefinition provider(URI endpoint) {
        ModelProviderId providerId = new ModelProviderId("opencode-go");
        ModelDefinition model = new ModelDefinition(
                new ModelDefinitionId("opencode-go-deepseek-flash"),
                "model-v1",
                providerId,
                "deepseek-v4.1-flash",
                "OpenCode Go DeepSeek V4.1 Flash",
                ModelStatus.ACTIVE,
                EnumSet.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING, ModelCapability.REASONING),
                1_000_000,
                384_000,
                Map.of(),
                Map.of(),
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS);
        return new ModelProviderDefinition(
                providerId,
                "provider-v1",
                "OpenCode Go",
                endpoint,
                new CredentialRef("model-auth://opencode-go/default"),
                true,
                ProviderStatus.ACTIVE,
                List.of(new ModelApiBindingDefinition(
                        ModelApiStyles.OPENAI_CHAT_COMPLETIONS, OpenAiCompatibleDialects.OPENCODE_GO)),
                List.of(model),
                Map.of(),
                Map.of());
    }

    private void handle(HttpExchange exchange) throws IOException {
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        sessionHeader.set(exchange.getRequestHeaders().getFirst(SESSION_HEADER));
        userAgentHeader.set(exchange.getRequestHeaders().getFirst("User-Agent"));
        String body = responseBody.get();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders()
                .set(
                        "Content-Type",
                        body.startsWith("data:") ? "text/event-stream; charset=utf-8" : "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
