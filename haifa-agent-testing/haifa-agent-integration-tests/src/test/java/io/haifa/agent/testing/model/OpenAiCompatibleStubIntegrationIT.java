package io.haifa.agent.testing.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.ModelCallId;
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelProviderDefinition;
import io.haifa.agent.model.api.ResolvedCredential;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.model.openai.DeepSeekDefaults;
import io.haifa.agent.model.openai.OpenAiCompatibleChatModel;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleStubIntegrationIT {
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<JsonNode> request = new AtomicReference<>();
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private byte[] response;

    @BeforeEach
    void startLoopbackStub() throws Exception {
        try (var input = Objects.requireNonNull(
                getClass().getResourceAsStream("/fixtures/http/openai-compatible/final-answer.json"))) {
            response = input.readAllBytes();
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", this::respond);
        server.start();
    }

    @AfterEach
    void stopLoopbackStub() {
        server.stop(0);
    }

    @Test
    void mapsSharedFixtureThroughRealAdapterWithoutExternalNetwork() {
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        ModelProviderDefinition defaults = DeepSeekDefaults.provider();
        ModelProviderDefinition provider = new ModelProviderDefinition(
                defaults.id(),
                defaults.version(),
                defaults.displayName(),
                defaults.adapterType(),
                endpoint,
                defaults.credentialRef(),
                defaults.status(),
                defaults.models(),
                defaults.options(),
                defaults.metadata());
        var definition = provider.models().getFirst();
        var snapshot = ResolvedModelSnapshot.create(
                provider.id(),
                provider.version(),
                definition.id(),
                definition.version(),
                definition.providerModelId(),
                provider.adapterType(),
                "1.0.0",
                endpoint,
                provider.credentialRef(),
                definition.capabilities(),
                definition.contextWindow(),
                definition.maxOutputTokens(),
                provider.options(),
                Map.of("thinking", "disabled"));
        var model = new OpenAiCompatibleChatModel(
                provider,
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                json,
                ignored -> new ResolvedCredential("fixture-secret"),
                true,
                1024 * 1024);

        var actual = model.invoke(new AgentChatRequest(
                new ModelCallId("fixture-call"),
                new AgentRunId("fixture-run"),
                1,
                1,
                snapshot,
                List.of(ModelMessage.text(ModelMessageRole.USER, "fixture request")),
                List.of(),
                64,
                Duration.ofSeconds(5),
                Map.of()));

        assertThat(actual.content()).isEqualTo("FIXTURE_OK");
        assertThat(actual.usage().inputTokens()).isEqualTo(4);
        assertThat(actual.usage().outputTokens()).isEqualTo(2);
        assertThat(authorization.get()).isEqualTo("Bearer fixture-secret");
        assertThat(request.get().path("thinking").path("type").asText()).isEqualTo("disabled");
    }

    private void respond(HttpExchange exchange) throws IOException {
        try (exchange) {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            request.set(json.readTree(exchange.getRequestBody()));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
        }
    }
}
