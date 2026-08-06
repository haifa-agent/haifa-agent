package io.haifa.agent.personalassistant.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.execution.core.tool.ExecutionOperatingSystem;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.config.location=classpath:/application-deterministic-model.yml")
@AutoConfigureWebTestClient
class PersonalAssistantWebFluxTest {
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration RUN_STATUS_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration RUN_STATUS_POLL_INTERVAL = Duration.ofMillis(100);
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
                .responseTimeout(HTTP_TIMEOUT)
                .build();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("haifa.personal.data-directory", DATA::toString);
        registry.add("haifa.personal.continuation-key-base64", () -> Base64.getEncoder()
                .encodeToString(new byte[32]));
        registry.add("haifa.personal.mcp.port", () -> MCP_PORT);
        registry.add("haifa.personal.execution.trusted-host-enabled", () -> "true");
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
                .value(value -> assertThat(value.toString())
                        .contains("tool", "skill", "mcp")
                        .doesNotContain("admin"));

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
            assertThat(java.util.stream.StreamSupport.stream(activities.spliterator(), false)
                            .toList())
                    .anySatisfy(activity -> {
                        assertThat(activity.path("kind").asText()).isEqualTo("MODEL");
                        assertThat(activity.path("displayName").asText()).isNotBlank();
                        assertThat(activity.path("safeTargetSummary").asText()).contains("iteration", "attempt");
                        assertThat(activity.path("status").asText()).isEqualTo("SUCCEEDED");
                        assertThat(activity.path("safeResultSummary").asText()).contains("Input", "Output");
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
        assertThat(observedKinds).containsExactlyInAnyOrder("MODEL", "TOOL", "SKILL", "MCP");
    }

    @Test
    void uploadedImageFlowsThroughTheConversationAndRemainsAnOpaqueTurnReference() throws Exception {
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1};
        String uploadBody = web.post()
                .uri("/api/v1/images")
                .header("X-Haifa-CSRF", "1")
                .header("Idempotency-Key", "image-" + IDS.incrementAndGet())
                .header("X-Image-Filename", "cat.png")
                .contentType(MediaType.IMAGE_PNG)
                .bodyValue(png)
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        String imageId = mapper.readTree(uploadBody).path("imageId").asText();

        JsonNode conversation = post(
                "/api/v1/conversations",
                """
                {"displayName":"Image","message":"Describe this image","images":[{"kind":"upload","imageId":%s}]}
                """
                        .formatted(mapper.writeValueAsString(imageId)));
        assertThat(awaitTerminal(conversation.path("activeRunId").asText())
                        .path("status")
                        .asText())
                .isEqualTo("COMPLETED");

        web.get()
                .uri("/api/v1/conversations/{id}/turns", conversation.path("id").asText())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].images[0].kind")
                .isEqualTo("upload")
                .jsonPath("$[0].images[0].imageId")
                .isEqualTo(imageId)
                .jsonPath("$[0].images[0].url")
                .doesNotExist();
    }

    @Test
    void imageUploadMayUseTheDocumentedBudgetBeyondTheDefaultApiBodyLimit() {
        byte[] png = new byte[70 * 1024];
        byte[] signature = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        System.arraycopy(signature, 0, png, 0, signature.length);

        web.post()
                .uri("/api/v1/images")
                .header("X-Haifa-CSRF", "1")
                .header("Idempotency-Key", "large-image-" + IDS.incrementAndGet())
                .header("X-Image-Filename", "large.png")
                .contentType(MediaType.IMAGE_PNG)
                .bodyValue(png)
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody()
                .jsonPath("$.sizeBytes")
                .isEqualTo(png.length);
    }

    @Test
    void recommendationEndpointBindsToTheCompletedAnswerAndAllowsAnEmptyResult() throws Exception {
        JsonNode conversation = post(
                "/api/v1/conversations",
                """
                {"displayName":"Recommendations","message":"What is 2 + 2?"}
                """);
        String conversationId = conversation.path("id").asText();
        String runId = conversation.path("activeRunId").asText();
        assertThat(awaitTerminal(runId).path("status").asText()).isEqualTo("COMPLETED");

        JsonNode result =
                post("/api/v1/conversations/" + conversationId + "/runs/" + runId + "/recommend-questions", "{}");

        assertThat(result.path("questions").isArray()).isTrue();
        assertThat(result.path("questions")).isEmpty();
        assertThat(get("/api/v1/bootstrap").path("capabilities").toString()).contains("recommended-questions");
    }

    @Test
    void executionRequiresExactApprovalAndPublishesSafeActivity() throws Exception {
        JsonNode conversation = post(
                "/api/v1/conversations",
                """
                {"displayName":"Execution acceptance","message":"[execution-script]"}
                """);
        String runId = conversation.path("activeRunId").asText();
        JsonNode waiting = awaitStatus(runId, Set.of("WAITING_APPROVAL"));
        assertThat(waiting.path("status").asText()).isEqualTo("WAITING_APPROVAL");

        JsonNode interaction = get("/api/v1/runs/" + runId + "/interaction");
        assertThat(interaction.path("kind").asText()).isEqualTo("approval");
        assertThat(interaction.path("allowedActions").toString()).contains("approve", "reject");
        assertThat(interaction.path("safePrompt").asText())
                .contains(
                        "Mode: SCRIPT",
                        "Language: " + expectedScriptLanguage(),
                        "Purpose: " + expectedArgumentEchoPurpose(),
                        "Risks: HIGH",
                        expectedArgumentEchoScript())
                .doesNotContain("operatingSystem", "executable");

        post(
                "/api/v1/runs/" + runId + "/interactions/"
                        + interaction.path("id").asText() + "/response",
                interaction.path("revision").asLong(),
                """
                {"action":"approve","text":null}
        """);
        JsonNode completed = awaitTerminal(runId);
        JsonNode activities = get("/api/v1/runs/" + runId + "/activities");
        assertThat(completed.path("status").asText())
                .as(completed.toPrettyString() + "\n" + activities.toPrettyString())
                .isEqualTo("COMPLETED");
        assertThat(activities.toString())
                .contains("execution_run", "SCRIPT", expectedScriptLanguage(), expectedArgumentEchoPurpose())
                .contains("first argument|second'argument");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void powerShellCommandRequiresExactApprovalAndPublishesSafeActivity() throws Exception {
        JsonNode conversation = post(
                "/api/v1/conversations",
                """
                {"displayName":"PowerShell command acceptance","message":"[execution-command]"}
                """);
        String runId = conversation.path("activeRunId").asText();
        JsonNode waiting = awaitStatus(runId, Set.of("WAITING_APPROVAL"));

        JsonNode interaction = get("/api/v1/runs/" + runId + "/interaction");
        assertThat(interaction.path("safePrompt").asText())
                .contains(
                        "Mode: COMMAND",
                        "Language: default-shell",
                        "Purpose: 读取当前 PowerShell 版本",
                        "$PSVersionTable.PSVersion.ToString()",
                        "Risks: HIGH");

        post(
                "/api/v1/runs/" + runId + "/interactions/"
                        + interaction.path("id").asText() + "/response",
                interaction.path("revision").asLong(),
                """
                {"action":"approve","text":null}
        """);
        JsonNode completed = awaitTerminal(runId);
        JsonNode activities = get("/api/v1/runs/" + runId + "/activities");
        assertThat(completed.path("status").asText())
                .as(completed.toPrettyString() + "\n" + activities.toPrettyString())
                .isEqualTo("COMPLETED");
        assertThat(activities.toString()).contains("execution_run", "COMMAND", "读取当前 PowerShell 版本");
        assertThat(java.util.stream.StreamSupport.stream(activities.spliterator(), false)
                        .toList())
                .anySatisfy(activity -> assertThat(
                                activity.path("safeResultSummary").asText())
                        .matches("\\d+\\.\\d+(?:\\.\\d+){0,2}\\s*"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void approvedPowerShellDiskQueryCompletesThroughTheGuardedHost() throws Exception {
        JsonNode conversation = post(
                "/api/v1/conversations",
                """
                {"displayName":"PowerShell disk query","message":"[execution-disk]"}
                """);
        String runId = conversation.path("activeRunId").asText();
        JsonNode waiting = awaitStatus(runId, Set.of("WAITING_APPROVAL"));
        assertThat(waiting.path("status").asText()).isEqualTo("WAITING_APPROVAL");

        JsonNode interaction = get("/api/v1/runs/" + runId + "/interaction");
        assertThat(interaction.path("safePrompt").asText())
                .contains("Mode: COMMAND", "Get-PSDrive -PSProvider FileSystem", "Risks: HIGH");
        post(
                "/api/v1/runs/" + runId + "/interactions/"
                        + interaction.path("id").asText() + "/response",
                interaction.path("revision").asLong(),
                """
                {"action":"approve","text":null}
                """);

        JsonNode completed = awaitTerminal(runId);
        JsonNode activities = get("/api/v1/runs/" + runId + "/activities");
        assertThat(completed.path("status").asText())
                .as(completed.toPrettyString() + "\n" + activities.toPrettyString())
                .isEqualTo("COMPLETED");
        assertThat(activities.toString()).contains("execution_run", "COMMAND", "Inspect filesystem drive usage");
    }

    @Test
    void cpuObservationScriptWaitsForExactApproval() throws Exception {
        JsonNode conversation = post(
                "/api/v1/conversations",
                """
                {"displayName":"CPU observation","message":"请看下当前系统的CPU使用率 [execution-cpu]"}
                """);
        String runId = conversation.path("activeRunId").asText();

        JsonNode waiting = awaitStatus(runId, Set.of("WAITING_APPROVAL", "FAILED"));
        assertThat(waiting.path("status").asText()).isEqualTo("WAITING_APPROVAL");
        JsonNode interaction = get("/api/v1/runs/" + runId + "/interaction");
        assertThat(interaction.path("safePrompt").asText())
                .contains(
                        "Mode: SCRIPT",
                        "Language: " + expectedScriptLanguage(),
                        "读取当前系统 CPU 使用率与逻辑处理器数量",
                        expectedCpuProbe());
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
    void adminListsFrozenToolMcpAndSkillRegistrationsWithoutRuntimeSecrets() throws Exception {
        JsonNode capabilities = get("/v1/admin/capabilities");

        assertThat(capabilities.path("toolCatalogDigest").asText()).isNotBlank();
        assertThat(capabilities.path("skillCatalogDigest").asText()).isNotBlank();
        assertThat(capabilities.path("skillResolutionPolicy").asText()).isNotBlank();
        assertThat(java.util.stream.StreamSupport.stream(
                                capabilities.path("registrations").spliterator(), false)
                        .map(registration -> registration.path("kind").asText())
                        .toList())
                .contains("TOOL", "MCP", "SKILL");

        String snapshot = capabilities.toString();
        assertThat(snapshot)
                .contains(
                        "execution_run",
                        "personal-local",
                        "personal_mcp_echo",
                        "2025-11-25",
                        "daily-planning",
                        "local-script-execution",
                        "SKILL.md")
                .doesNotContain("continuation-key-base64", "sessionId", "credentialValue", "resolvedCredential");
    }

    @Test
    void adminBuildsOneRunTreeWithoutExposingPromptOrToolPayloads() throws Exception {
        String sensitivePrompt = "[tool] private-admin-prompt-7f29";
        JsonNode conversation = post(
                "/api/v1/conversations",
                """
                {"displayName":"Admin diagnostics","message":%s}
                """
                        .formatted(mapper.writeValueAsString(sensitivePrompt)));
        String sessionId = conversation.path("id").asText();
        String runId = conversation.path("activeRunId").asText();
        assertThat(awaitTerminal(runId).path("status").asText()).isEqualTo("COMPLETED");

        JsonNode sessions = get("/v1/admin/sessions");
        assertThat(sessions.toString()).contains(sessionId);
        JsonNode runs = get("/v1/admin/sessions/" + sessionId + "/runs");
        assertThat(runs.toString()).contains(runId, "Objective hidden").doesNotContain(sensitivePrompt);

        JsonNode tree = get("/v1/admin/sessions/" + sessionId + "/runs/" + runId + "/tree");
        assertThat(tree.path("root").path("id").asText()).isEqualTo("run:" + runId);
        assertThat(tree.toString())
                .contains("Frozen agent and model configuration", "personal_checklist", "contentHidden")
                .doesNotContain(sensitivePrompt, "review the plan", "confirm completion");
        assertThat(java.util.stream.StreamSupport.stream(tree.path("nodes").spliterator(), false)
                        .map(node -> node.path("kind").asText())
                        .toList())
                .contains("configuration", "message", "attempt", "step", "tool", "event");
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

        web.options()
                .uri("/v1/admin/sessions")
                .header("Origin", "http://127.0.0.1:20000")
                .header("Access-Control-Request-Method", "GET")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Access-Control-Allow-Origin", "http://127.0.0.1:20000");
    }

    private JsonNode post(String uri, String json) throws Exception {
        return post(uri, null, json);
    }

    private JsonNode post(String uri, Long revision, String json) throws Exception {
        WebTestClient.RequestBodySpec request = web.post()
                .uri(uri)
                .header("X-Haifa-CSRF", "1")
                .header("Idempotency-Key", "test-" + IDS.incrementAndGet())
                .contentType(MediaType.APPLICATION_JSON);
        if (revision != null) request.header("If-Match", '"' + revision.toString() + '"');
        byte[] body = request.bodyValue(json)
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
        return awaitStatus(runId, Set.of("COMPLETED", "FAILED", "CANCELLED", "TIMEOUT"));
    }

    private static String expectedScriptLanguage() {
        return ExecutionOperatingSystem.current() == ExecutionOperatingSystem.WINDOWS ? "powershell" : "bash";
    }

    private static String expectedArgumentEchoScript() {
        return ExecutionOperatingSystem.current() == ExecutionOperatingSystem.WINDOWS
                ? "$args -join '|'"
                : "printf '%s|%s' \"$1\" \"$2\"";
    }

    private static String expectedArgumentEchoPurpose() {
        return "验证 "
                + (ExecutionOperatingSystem.current() == ExecutionOperatingSystem.WINDOWS ? "PowerShell" : "Bash")
                + " 脚本参数通过 stdin 安全传递";
    }

    private static String expectedCpuProbe() {
        return switch (ExecutionOperatingSystem.current()) {
            case WINDOWS -> "Get-CimInstance Win32_Processor";
            case MACOS -> "top -l 2 -n 0";
            case LINUX -> "/proc/stat";
        };
    }

    private JsonNode awaitStatus(String runId, Set<String> expected) throws Exception {
        long deadline = System.nanoTime() + RUN_STATUS_TIMEOUT.toNanos();
        JsonNode latest;
        do {
            latest = get("/api/v1/runs/" + runId);
            if (expected.contains(latest.path("status").asText())) {
                return latest;
            }
            Thread.sleep(RUN_STATUS_POLL_INTERVAL);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("run did not become terminal: " + latest);
    }

    private static Path temporaryDirectory() {
        try {
            Path root = Path.of("target", "personal-webflux-" + UUID.randomUUID())
                    .toAbsolutePath()
                    .normalize();
            return Files.createDirectories(root);
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
