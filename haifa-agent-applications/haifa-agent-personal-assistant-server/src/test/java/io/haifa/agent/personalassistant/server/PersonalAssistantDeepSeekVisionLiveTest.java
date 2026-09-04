package io.haifa.agent.personalassistant.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Live-only DeepSeek Vision smoke; raw model text and image bytes never enter Harness evidence. */
@SpringBootTest(
        classes = PersonalAssistantServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Tag("slow")
@EnabledIfEnvironmentVariable(named = "HAIFA_PERSONAL_LIVE_SMOKE", matches = "true")
class PersonalAssistantDeepSeekVisionLiveTest {
    private static final String MODEL_ID = "deepseek-v4-flash-vision-exp";
    private static final String FIXTURE =
            "fixtures/personal-assistant/deepseek-vision-live-v1/indoor-door-people.webp";
    private static final String FIXTURE_SHA256 = "b02eb0f560b43ffd898a094db0aa36d54959513f807fed35d032cafe946ffbf5";
    private static final Path DATA = temporaryDirectory();
    private static final int MCP_PORT = freeMcpPort();
    private static final Duration TERMINAL_TIMEOUT = Duration.ofMinutes(2);

    @Autowired
    ObjectMapper mapper;

    @LocalServerPort
    int serverPort;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("haifa.personal.data-directory", () -> DATA.toString());
        registry.add("haifa.personal.default-model-id", () -> MODEL_ID);
        registry.add("haifa.personal.continuation-key-base64", () -> Base64.getEncoder()
                .encodeToString(new byte[32]));
        registry.add("haifa.personal.execution.trusted-host-enabled", () -> "true");
        registry.add("haifa.personal.mcp.port", () -> MCP_PORT);
    }

    @Test
    void uploadsWebpAndVerifiesADeepSeekVisionResponse() throws Exception {
        Path image = fixture();
        byte[] bytes = Files.readAllBytes(image);
        assertThat(sha256(bytes)).isEqualTo(FIXTURE_SHA256);

        WebTestClient web = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + serverPort)
                .responseTimeout(Duration.ofSeconds(30))
                .build();
        String imageId = mapper.readTree(web.post()
                        .uri("/api/v1/images")
                        .header("X-Haifa-CSRF", "1")
                        .header("Idempotency-Key", "vision-upload-" + UUID.randomUUID())
                        .header("X-Image-Filename", "indoor-door-people.webp")
                        .contentType(MediaType.parseMediaType("image/webp"))
                        .bodyValue(bytes)
                        .exchange()
                        .expectStatus()
                        .isCreated()
                        .expectBody()
                        .returnResult()
                        .getResponseBody())
                .path("imageId")
                .asText();
        assertThat(imageId).isNotBlank();

        var request = mapper.createObjectNode();
        request.put("displayName", "DeepSeek Vision live smoke");
        request.put(
                "message",
                "Answer exactly YES if the uploaded image shows people indoors and a door. Answer exactly NO otherwise.");
        request.put("modelId", MODEL_ID);
        var images = request.putArray("images");
        var upload = images.addObject();
        upload.put("kind", "upload");
        upload.put("imageId", imageId);
        JsonNode conversation = mapper.readTree(web.post()
                        .uri("/api/v1/conversations")
                        .header("X-Haifa-CSRF", "1")
                        .header("Idempotency-Key", "vision-conversation-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(mapper.writeValueAsString(request))
                        .exchange()
                        .expectStatus()
                        .isCreated()
                        .expectBody()
                        .returnResult()
                        .getResponseBody());

        String conversationId = conversation.path("id").asText();
        assertThat(conversationId).isNotBlank();
        awaitCompleted(web, conversation.path("activeRunId").asText());
        JsonNode turns = mapper.readTree(web.get()
                        .uri("/api/v1/conversations/{conversationId}/turns", conversationId)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody()
                        .returnResult()
                        .getResponseBody());
        boolean affirmed = false;
        for (JsonNode turn : turns) {
            if ("ASSISTANT".equalsIgnoreCase(turn.path("role").asText())
                    && "YES".equals(turn.path("text").asText().trim().toUpperCase(java.util.Locale.ROOT))) {
                affirmed = true;
            }
        }
        assertThat(affirmed).isTrue();
    }

    private static void awaitCompleted(WebTestClient web, String runId) throws Exception {
        assertThat(runId).isNotBlank();
        long deadline = System.nanoTime() + TERMINAL_TIMEOUT.toNanos();
        do {
            JsonNode run = new ObjectMapper().readTree(web.get()
                    .uri("/api/v1/runs/{runId}", runId)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .returnResult()
                    .getResponseBody());
            if ("COMPLETED".equals(run.path("status").asText())) return;
            if (Set.of("FAILED", "CANCELLED", "TIMEOUT").contains(run.path("status").asText())) {
                throw new AssertionError("live vision run did not complete");
            }
            Thread.sleep(200);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("live vision run did not complete within the smoke timeout");
    }

    private static Path fixture() {
        String configRoot = System.getenv("HAIFA_TEST_CONFIG_ROOT");
        if (configRoot == null || configRoot.isBlank()) {
            throw new IllegalStateException("HAIFA_TEST_CONFIG_ROOT is required for the live vision fixture");
        }
        Path root = Path.of(configRoot).toAbsolutePath().normalize();
        Path fixture = root.resolve(FIXTURE).normalize();
        if (!fixture.startsWith(root) || !Files.isRegularFile(fixture)) {
            throw new IllegalStateException("live vision fixture is unavailable");
        }
        return fixture;
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static Path temporaryDirectory() {
        try {
            String runRoot = System.getenv("HAIFA_TEST_RUN_ROOT");
            if (runRoot == null || runRoot.isBlank()) {
                return Files.createTempDirectory("haifa-personal-deepseek-vision-").toAbsolutePath();
            }
            return Files.createTempDirectory(
                            Path.of(runRoot).toAbsolutePath().normalize(), "deepseek-vision-")
                    .toAbsolutePath();
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static int freeMcpPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
