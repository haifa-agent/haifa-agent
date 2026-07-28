package io.haifa.agent.personalassistant.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class PersonalAssistantWebFluxTest {
    private static final Path DATA = temporaryDirectory();
    private static final int MCP_PORT = freeMcpPort();
    private static final AtomicInteger IDS = new AtomicInteger();

    @Autowired
    WebTestClient web;

    @Autowired
    ObjectMapper mapper;

    @LocalServerPort
    int serverPort;

    @BeforeEach
    void useExplicitLoopbackHost() {
        web = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + serverPort)
                .build();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("haifa.personal.data-directory", DATA::toString);
        registry.add("haifa.personal.continuation-key-base64", () -> Base64.getEncoder()
                .encodeToString(new byte[32]));
        registry.add("haifa.personal.model.mode", () -> "deterministic");
        registry.add("haifa.personal.model.allow-deterministic", () -> "true");
        registry.add("haifa.personal.model.endpoint", () -> "http://127.0.0.1:20999");
        registry.add("haifa.personal.model.provider-model-id", () -> "personal-test");
        registry.add("haifa.personal.model.credential-reference", () -> "env://UNUSED");
        registry.add("haifa.personal.mcp.port", () -> MCP_PORT);
    }

    @Test
    void webfluxApiExecutesToolSkillAndMcpThroughOneRuntimePipeline() throws Exception {
        web.get()
                .uri("/api/v1/bootstrap")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.capabilities")
                .value(value -> assertThat(value.toString()).contains("tool", "skill", "mcp"));

        Set<String> observedKinds = new HashSet<>();
        for (var vertical : java.util.List.of(
                new Vertical("[tool] verify checklist", "TOOL"),
                new Vertical("[skill] load daily planning", "SKILL"),
                new Vertical("[mcp] verify local echo", "MCP"))) {
            JsonNode conversation = post(
                    "/api/v1/conversations",
                    """
                    {"displayName":"Acceptance","message":%s}
                    """
                            .formatted(mapper.writeValueAsString(vertical.prompt())));
            String runId = conversation.path("activeRunId").asText();
            assertThat(runId).isNotBlank();
            JsonNode run = awaitTerminal(runId);
            assertThat(run.path("status").asText()).isEqualTo("COMPLETED");
            assertThat(run.path("usage").path("inputTokens").asLong()).isPositive();
            assertThat(run.path("usage").path("outputTokens").asLong()).isPositive();
            assertThat(run.path("usage").path("totalTokens").asLong())
                    .isEqualTo(run.path("usage").path("inputTokens").asLong()
                            + run.path("usage").path("outputTokens").asLong());
            assertThat(run.path("usage").path("modelCalls").asLong()).isEqualTo(2);
            assertThat(run.path("usage").path("toolCalls").asLong()).isEqualTo(1);

            JsonNode activities = get("/api/v1/runs/" + runId + "/activities");
            assertThat(activities.isArray()).isTrue();
            assertThat(java.util.stream.StreamSupport.stream(activities.spliterator(), false)
                            .toList())
                    .anySatisfy(activity -> {
                        assertThat(activity.path("kind").asText()).isEqualTo(vertical.kind());
                        assertThat(activity.path("safeResultSummary").asText()).isNotBlank();
                    });
            activities.forEach(
                    activity -> observedKinds.add(activity.path("kind").asText()));

            var sse = web.get()
                    .uri("/api/v1/runs/" + runId + "/stream")
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .returnResult(String.class)
                    .getResponseBody()
                    .collectList()
                    .block(Duration.ofSeconds(5));
            assertThat(sse).isNotEmpty();
        }
        assertThat(observedKinds).containsExactlyInAnyOrder("TOOL", "SKILL", "MCP");
    }

    @Test
    void mutationsRequireCsrfAndIdempotencyHeaders() {
        web.post()
                .uri("/api/v1/conversations")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"displayName\":\"Blocked\",\"message\":\"hello\"}")
                .exchange()
                .expectStatus()
                .isForbidden();

        web.post()
                .uri("/api/v1/conversations")
                .header("X-Haifa-CSRF", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"displayName\":\"Missing key\",\"message\":\"hello\"}")
                .exchange()
                .expectStatus()
                .is4xxClientError();
    }

    @Test
    void publishesTheVersionedOpenApiContract() {
        web.get()
                .uri("/api/v1/openapi.json")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.openapi")
                .isEqualTo("3.1.0")
                .jsonPath("$.paths['/api/v1/runs/{runId}/stream'].get.responses['200'].content['text/event-stream']")
                .exists();
    }

    @Test
    void doesNotServeFrontendRoutes() {
        web.get().uri("/").exchange().expectStatus().isNotFound();
        web.get().uri("/conversation/local-history").exchange().expectStatus().isNotFound();
        web.get().uri("/api/v1/not-a-route").exchange().expectStatus().isNotFound();
    }

    @Test
    void allowsTheStandaloneLoopbackWebOrigin() {
        web.options()
                .uri("/api/v1/conversations")
                .header("Origin", "http://127.0.0.1:20000")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Content-Type,X-Haifa-CSRF,Idempotency-Key")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Access-Control-Allow-Origin", "http://127.0.0.1:20000")
                .expectHeader()
                .doesNotExist("Access-Control-Allow-Credentials");
    }

    private JsonNode post(String uri, String json) throws Exception {
        byte[] body = web.post()
                .uri(uri)
                .header("X-Haifa-CSRF", "1")
                .header("Idempotency-Key", "test-" + IDS.incrementAndGet())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(json)
                .exchange()
                .expectStatus()
                .is2xxSuccessful()
                .expectBody()
                .returnResult()
                .getResponseBody();
        return mapper.readTree(body);
    }

    private JsonNode get(String uri) throws Exception {
        byte[] body = web.get()
                .uri(uri)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .returnResult()
                .getResponseBody();
        return mapper.readTree(body);
    }

    private JsonNode awaitTerminal(String runId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        JsonNode latest;
        do {
            latest = get("/api/v1/runs/" + runId);
            if (Set.of("COMPLETED", "FAILED", "CANCELLED", "TIMEOUT")
                    .contains(latest.path("status").asText())) {
                return latest;
            }
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("run did not become terminal: " + latest);
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("haifa-personal-webflux-");
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static int freeMcpPort() {
        for (int port = 22001; port < 22100; port++) {
            try (ServerSocket socket = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
                return socket.getLocalPort();
            } catch (IOException ignored) {
                // Try the next explicit port above the production MCP default.
            }
        }
        throw new IllegalStateException("no free Personal MCP test port");
    }

    private record Vertical(String prompt, String kind) {}
}
