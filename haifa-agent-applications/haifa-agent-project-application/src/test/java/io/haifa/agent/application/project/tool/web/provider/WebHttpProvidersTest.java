package io.haifa.agent.application.project.tool.web.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.haifa.agent.application.project.tool.web.WebContentFormat;
import io.haifa.agent.application.project.tool.web.WebDispatchState;
import io.haifa.agent.application.project.tool.web.WebFetchRequest;
import io.haifa.agent.application.project.tool.web.WebFreshness;
import io.haifa.agent.application.project.tool.web.WebProviderException;
import io.haifa.agent.application.project.tool.web.WebProviderInvocationContext;
import io.haifa.agent.application.project.tool.web.WebSafeSearch;
import io.haifa.agent.application.project.tool.web.WebSearchRequest;
import io.haifa.agent.credential.api.CredentialLease;
import io.haifa.agent.credential.api.CredentialReference;
import io.haifa.agent.credential.api.SecretFunction;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebHttpProvidersTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private URI baseUri;
    private final AtomicReference<CapturedRequest> captured = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> response = new AtomicReference<>("{}");

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void aliyunSearchUsesBearerAndMapsStructuredResults() {
        response.set(
                """
                {"requestId":"ali-1","pageItems":[
                  {"title":"Haifa","link":"https://example.com/haifa","snippet":"Agent runtime","rerankScore":0.9}
                ]}
                """);
        var provider = new AliyunSearchProvider(
                client(), mapper, baseUri.resolve("/search/unified"), Duration.ofSeconds(5), 64 * 1024);

        var request = new WebSearchRequest(
                "agent runtime",
                3,
                Optional.empty(),
                Optional.empty(),
                Optional.of(WebFreshness.MONTH),
                List.of("example.com"),
                List.of("spam.example"),
                Optional.empty());

        var result = provider.search(request, context());

        assertThat(result.results()).hasSize(1);
        assertThat(result.results().getFirst().title()).isEqualTo("Haifa");
        assertThat(captured.get().authorization()).isEqualTo("Bearer test-key");
        assertThat(captured.get()
                        .body()
                        .path("advancedParams")
                        .path("numResults")
                        .asInt())
                .isEqualTo(3);
        assertThat(captured.get().body().path("contents").path("mainText").asBoolean())
                .isFalse();
        assertThat(captured.get().body().path("timeRange").asText()).isEqualTo("OneMonth");
        assertThat(captured.get()
                        .body()
                        .path("advancedParams")
                        .path("includeSites")
                        .asText())
                .isEqualTo("example.com");
        assertThat(captured.get()
                        .body()
                        .path("advancedParams")
                        .path("excludeSites")
                        .asText())
                .isEqualTo("spam.example");
    }

    @Test
    void aliyunFetchOnlyCallsIqsAndSelectsRequestedContent() {
        response.set(
                """
                {"data":{"url":"https://example.com/page","title":"Page","markdown":"# Page","text":"Page"}}
                """);
        var provider = new AliyunFetchProvider(
                client(), mapper, baseUri.resolve("/readpage/basic"), Duration.ofSeconds(5), 64 * 1024);

        var result = provider.fetch(
                new WebFetchRequest(URI.create("https://example.com/page"), WebContentFormat.MARKDOWN, 100), context());

        assertThat(result.content()).isEqualTo("# Page");
        assertThat(result.format()).isEqualTo(WebContentFormat.MARKDOWN);
        assertThat(captured.get().path()).isEqualTo("/readpage/basic");
        assertThat(captured.get().body().path("url").asText()).isEqualTo("https://example.com/page");
        assertThat(captured.get().apiKey()).isEqualTo("test-key");
    }

    @Test
    void braveMapsQueryOptionsAndHeader() {
        response.set(
                """
                {"query":{"original":"agent runtime"},"web":{"results":[
                  {"title":"Result","url":"https://example.com/result","description":"Snippet"}
                ]}}
                """);
        var provider = new BraveWebSearchProvider(
                client(), mapper, baseUri.resolve("/brave"), Duration.ofSeconds(5), 64 * 1024);
        var request = new WebSearchRequest(
                "agent runtime",
                3,
                Optional.of("en"),
                Optional.of("US"),
                Optional.of(WebFreshness.WEEK),
                List.of(),
                List.of(),
                Optional.of(WebSafeSearch.STRICT));

        var result = provider.search(request, context());

        assertThat(result.results()).hasSize(1);
        assertThat(captured.get().query())
                .contains("q=agent%20runtime")
                .contains("freshness=pw")
                .contains("safesearch=strict");
        assertThat(captured.get().subscriptionToken()).isEqualTo("test-key");
    }

    @Test
    void tavilyDisablesAnswerRawContentImagesAndAutoParameters() {
        response.set(
                """
                {"query":"agent","request_id":"tv-1","results":[
                  {"title":"Result","url":"https://example.com/result","content":"Snippet","score":0.7}
                ]}
                """);
        var provider = new TavilyWebSearchProvider(
                client(), mapper, baseUri.resolve("/tavily"), Duration.ofSeconds(5), 64 * 1024);
        var request = new WebSearchRequest(
                "agent",
                3,
                Optional.empty(),
                Optional.of("china"),
                Optional.of(WebFreshness.MONTH),
                List.of("example.com"),
                List.of("excluded.example"),
                Optional.empty());

        var result = provider.search(request, context());

        JsonNode body = captured.get().body();
        assertThat(result.providerRequestId()).contains("tv-1");
        assertThat(body.path("search_depth").asText()).isEqualTo("basic");
        assertThat(body.path("include_answer").asBoolean()).isFalse();
        assertThat(body.path("include_raw_content").asBoolean()).isFalse();
        assertThat(body.path("include_images").asBoolean()).isFalse();
        assertThat(body.path("auto_parameters").asBoolean()).isFalse();
    }

    @Test
    void errorsDoNotReturnStubSuccessAndPreserveAcknowledgedState() {
        status.set(429);
        var provider = new BraveWebSearchProvider(
                client(), mapper, baseUri.resolve("/brave"), Duration.ofSeconds(5), 64 * 1024);

        assertThatThrownBy(() -> provider.search(searchRequest(), context()))
                .isInstanceOfSatisfying(WebProviderException.class, exception -> {
                    assertThat(exception.failureCode())
                            .isEqualTo(io.haifa.agent.application.project.tool.web.WebFailureCode.WEB_RATE_LIMITED);
                    assertThat(exception.dispatchState()).isEqualTo(WebDispatchState.ACKNOWLEDGED);
                });
    }

    @Test
    void malformedEmptyAndOversizedBodiesFailAfterAcknowledgement() {
        var provider =
                new BraveWebSearchProvider(client(), mapper, baseUri.resolve("/brave"), Duration.ofSeconds(5), 1024);

        response.set("not-json");
        CountingObserver malformedObserver = new CountingObserver();
        assertThatThrownBy(() -> provider.search(searchRequest(), context(malformedObserver)))
                .isInstanceOfSatisfying(WebProviderException.class, exception -> {
                    assertThat(exception.failureCode())
                            .isEqualTo(
                                    io.haifa.agent.application.project.tool.web.WebFailureCode
                                            .WEB_PROVIDER_RESPONSE_INVALID);
                    assertThat(exception.dispatchState()).isEqualTo(WebDispatchState.ACKNOWLEDGED);
                });
        assertThat(malformedObserver.dispatched.get()).isEqualTo(1);
        assertThat(malformedObserver.acknowledged.get()).isEqualTo(1);

        response.set("");
        assertThatThrownBy(() -> provider.search(searchRequest(), context()))
                .isInstanceOfSatisfying(WebProviderException.class, exception -> assertThat(exception.failureCode())
                        .isEqualTo(
                                io.haifa.agent.application.project.tool.web.WebFailureCode
                                        .WEB_PROVIDER_RESPONSE_INVALID));

        response.set("{\"padding\":\"" + "x".repeat(2048) + "\"}");
        assertThatThrownBy(() -> provider.search(searchRequest(), context()))
                .isInstanceOfSatisfying(WebProviderException.class, exception -> assertThat(exception.failureCode())
                        .isEqualTo(io.haifa.agent.application.project.tool.web.WebFailureCode.WEB_RESPONSE_TOO_LARGE));
    }

    @Test
    void mapsAuthenticationAndProviderFailuresWithoutLeakingResponseBody() {
        var provider = new BraveWebSearchProvider(
                client(), mapper, baseUri.resolve("/brave"), Duration.ofSeconds(5), 64 * 1024);

        response.set("{\"secret\":\"must-not-escape\"}");
        status.set(401);
        assertThatThrownBy(() -> provider.search(searchRequest(), context()))
                .isInstanceOfSatisfying(WebProviderException.class, exception -> {
                    assertThat(exception.failureCode())
                            .isEqualTo(io.haifa.agent.application.project.tool.web.WebFailureCode.WEB_AUTH_FAILED);
                    assertThat(exception.getMessage()).doesNotContain("must-not-escape");
                });

        status.set(500);
        assertThatThrownBy(() -> provider.search(searchRequest(), context()))
                .isInstanceOfSatisfying(WebProviderException.class, exception -> assertThat(exception.failureCode())
                        .isEqualTo(io.haifa.agent.application.project.tool.web.WebFailureCode.WEB_PROVIDER_FAILED));
    }

    @Test
    void rejectsMissingCredentialAndElapsedDeadlineBeforeDispatch() {
        CountingObserver missingObserver = new CountingObserver();
        var missingContext = new WebProviderInvocationContext(
                Instant.now().plusSeconds(30), () -> false, List.of(), missingObserver);
        var provider = new BraveWebSearchProvider(
                client(), mapper, baseUri.resolve("/brave"), Duration.ofSeconds(5), 64 * 1024);

        assertThatThrownBy(() -> provider.search(searchRequest(), missingContext))
                .isInstanceOfSatisfying(WebProviderException.class, exception -> {
                    assertThat(exception.failureCode())
                            .isEqualTo(
                                    io.haifa.agent.application.project.tool.web.WebFailureCode.WEB_CREDENTIAL_MISSING);
                    assertThat(exception.dispatchState()).isEqualTo(WebDispatchState.NOT_DISPATCHED);
                });
        assertThat(missingObserver.dispatched.get()).isZero();

        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        CountingObserver deadlineObserver = new CountingObserver();
        var deadlineProvider = new BraveWebSearchProvider(
                client(),
                mapper,
                baseUri.resolve("/brave"),
                Duration.ofSeconds(5),
                64 * 1024,
                Clock.fixed(now, ZoneOffset.UTC));
        var elapsedContext = new WebProviderInvocationContext(
                now.minusSeconds(1), () -> false, List.of(new TestLease("test-key")), deadlineObserver);
        assertThatThrownBy(() -> deadlineProvider.search(searchRequest(), elapsedContext))
                .isInstanceOfSatisfying(WebProviderException.class, exception -> {
                    assertThat(exception.failureCode())
                            .isEqualTo(io.haifa.agent.application.project.tool.web.WebFailureCode.WEB_TIMEOUT);
                    assertThat(exception.dispatchState()).isEqualTo(WebDispatchState.NOT_DISPATCHED);
                });
        assertThat(deadlineObserver.dispatched.get()).isZero();
    }

    private WebSearchRequest searchRequest() {
        return new WebSearchRequest(
                "agent runtime",
                3,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of(),
                Optional.empty());
    }

    private WebProviderInvocationContext context() {
        return context(new CountingObserver());
    }

    private WebProviderInvocationContext context(CountingObserver observer) {
        return new WebProviderInvocationContext(
                Instant.now().plusSeconds(30), () -> false, List.of(new TestLease("test-key")), observer);
    }

    private HttpClient client() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] requestBytes = exchange.getRequestBody().readAllBytes();
        JsonNode body = requestBytes.length == 0 ? mapper.createObjectNode() : mapper.readTree(requestBytes);
        captured.set(new CapturedRequest(
                exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getRawQuery(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("X-API-Key"),
                exchange.getRequestHeaders().getFirst("X-Subscription-Token"),
                body));
        byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status.get(), bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record CapturedRequest(
            String path, String query, String authorization, String apiKey, String subscriptionToken, JsonNode body) {}

    private static final class CountingObserver
            implements io.haifa.agent.application.project.tool.web.WebInvocationObserver {
        private final AtomicInteger dispatched = new AtomicInteger();
        private final AtomicInteger acknowledged = new AtomicInteger();

        @Override
        public void dispatched() {
            dispatched.incrementAndGet();
        }

        @Override
        public void acknowledged() {
            acknowledged.incrementAndGet();
        }
    }

    private static final class TestLease implements CredentialLease {
        private final byte[] secret;
        private boolean closed;

        private TestLease(String secret) {
            this.secret = secret.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public CredentialReference reference() {
            return new CredentialReference("test");
        }

        @Override
        public Instant expiresAt() {
            return Instant.now().plusSeconds(60);
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public <T> T use(SecretFunction<T> action) {
            if (closed) throw new IllegalStateException("closed");
            return action.apply(secret.clone());
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
