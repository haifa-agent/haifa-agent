package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.ModelMessageRole;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("slow")
class CodingHostServerTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path directory;

    @Test
    void servesTwoSessionsAndObservationCloseDoesNotCancelRuns() throws Exception {
        Path workspace = Files.createDirectory(directory.resolve("host-workspace"));
        Path alpha = Files.createDirectory(workspace.resolve("alpha"));
        Path beta = Files.createDirectory(workspace.resolve("beta"));
        Files.writeString(alpha.resolve("alpha-marker.txt"), "alpha");
        Files.writeString(beta.resolve("beta-marker.txt"), "beta");

        CountDownLatch bothFirstCalls = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ConcurrentHashMap<AgentRunId, AtomicInteger> calls = new ConcurrentHashMap<>();
        AgentChatModel model = request -> {
            int call = calls.computeIfAbsent(request.runId(), ignored -> new AtomicInteger())
                    .incrementAndGet();
            boolean alphaRequest = request.messages().stream()
                    .filter(message -> message.role() == ModelMessageRole.USER)
                    .anyMatch(message -> message.content().contains("alpha task"));
            if (call == 1) {
                bothFirstCalls.countDown();
                CodingHostTestFixture.await(release);
                return CodingHostTestFixture.toolResponse(
                        "host-read-" + request.runId().value(),
                        "file_list",
                        Map.of("path", (alphaRequest ? alpha : beta).toString()));
            }
            return CodingHostTestFixture.response(alphaRequest ? "alpha-answer" : "beta-answer");
        };

        try (var agent = agent(workspace, model);
                CodingHostServer server = CodingHostServer.start(agent, "127.0.0.1", 0)) {
            Rpc rpc = new Rpc(server.port());
            rpc.initialize();

            JsonNode createdA =
                    rpc.result("session/create", params("text", "alpha task", "idempotencyKey", "create-a"));
            String sessionA = createdA.path("session").path("sessionId").asText();
            String runA = createdA.path("activeRun").path("runId").asText();
            JsonNode createdB = rpc.result("session/create", params("text", "beta task", "idempotencyKey", "create-b"));
            String sessionB = createdB.path("session").path("sessionId").asText();
            String runB = createdB.path("activeRun").path("runId").asText();
            assertThat(sessionA).isNotEqualTo(sessionB);
            assertThat(runA).isNotEqualTo(runB);
            assertThat(createdA.path("outputCursor").asLong()).isZero();
            assertThat(createdA.path("previewAvailable").asBoolean()).isFalse();

            assertThat(bothFirstCalls.await(10, TimeUnit.SECONDS)).isTrue();
            JsonNode list = rpc.result("session/list", params());
            assertThat(list.path("sessions").size()).isEqualTo(2);

            JsonNode staleCancel = rpc.error(
                    "run/cancel",
                    params(
                            "sessionId", sessionA,
                            "runId", "not-the-active-run",
                            "idempotencyKey", "stale-cancel"));
            assertThat(staleCancel.path("data").path("reasonCode").asText()).isEqualTo("ACTIVE_RUN_MISMATCH");

            JsonNode subscription = rpc.result("events/subscribe", params("sessionId", sessionA, "runId", runA));
            String subscriptionId = subscription.path("subscriptionId").asText();
            HttpResponse<InputStream> stream = rpc.sse(subscriptionId);
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(stream.body(), StandardCharsets.UTF_8))) {
                String event = CompletableFuture.supplyAsync(() -> readEventData(reader))
                        .get(5, TimeUnit.SECONDS);
                assertThat(JSON.readTree(event).path("runId").asText()).isEqualTo(runA);
            } finally {
                stream.body().close();
            }
            rpc.result("events/unsubscribe", params("subscriptionId", subscriptionId));
            JsonNode outputSubscription = rpc.result("output/subscribe", params("sessionId", sessionA, "runId", runA));
            assertThat(outputSubscription.path("type").asText()).isEqualTo("output");
            rpc.result(
                    "output/unsubscribe",
                    params(
                            "subscriptionId",
                            outputSubscription.path("subscriptionId").asText()));

            release.countDown();
            awaitTerminal(rpc, runA);
            awaitTerminal(rpc, runB);

            JsonNode completedA = rpc.result("run/get", params("runId", runA));
            assertThat(completedA.path("output").asText()).isEqualTo("alpha-answer");
            assertThat(completedA.path("usage").path("inputTokens").isIntegralNumber())
                    .isTrue();
            JsonNode completedB = rpc.result("run/get", params("runId", runB));
            assertThat(completedB.path("output").asText()).isEqualTo("beta-answer");
            assertThat(rpc.result("session/open", params("sessionId", sessionA))
                            .path("history")
                            .path("items")
                            .size())
                    .isGreaterThan(0);
            assertThat(historyBodies(rpc.result("session/history", params("sessionId", sessionA, "limit", 100))))
                    .contains("alpha-answer")
                    .doesNotContain("beta-answer");
            assertThat(historyBodies(rpc.result("session/history", params("sessionId", sessionB, "limit", 100))))
                    .contains("beta-answer")
                    .doesNotContain("alpha-answer");
            assertRunScopedEvents(
                    rpc.result("events/page", params("sessionId", sessionA, "runId", runA)), runA, sessionA);
            assertRunScopedEvents(
                    rpc.result("events/page", params("sessionId", sessionB, "runId", runB)), runB, sessionB);
            JsonNode outputPage = rpc.result("output/page", params("sessionId", sessionA, "runId", runA));
            assertThat(outputPage.has("items")).isTrue();
            JsonNode cursorA = rpc.result("events/page", params("sessionId", sessionA, "runId", runA))
                    .path("headCursor");
            assertThat(rpc.error(
                                    "session/ackCursor",
                                    params("sessionId", sessionB, "cursor", JSON.convertValue(cursorA, Map.class)))
                            .path("data")
                            .path("reasonCode")
                            .asText())
                    .isEqualTo("SESSION_NOT_FOUND");
            assertThat(rpc.result("session/open", params("sessionId", sessionB))
                            .path("eventCursor")
                            .isNull())
                    .isTrue();
        }
    }

    @Test
    void maintenanceAdvancesQueuedFollowUpWithoutClientQueries() throws Exception {
        Path workspace = Files.createDirectory(directory.resolve("host-offline-queue"));
        CountDownLatch firstInFlight = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch followUpStarted = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<AgentRunId> firstRun =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<AgentRunId> followUpRun =
                new java.util.concurrent.atomic.AtomicReference<>();
        AgentChatModel model = request -> {
            AgentRunId first = firstRun.compareAndSet(null, request.runId()) ? request.runId() : firstRun.get();
            if (request.runId().equals(first)) {
                firstInFlight.countDown();
                CodingHostTestFixture.await(releaseFirst);
                return CodingHostTestFixture.response("first-answer");
            }
            followUpRun.set(request.runId());
            followUpStarted.countDown();
            return CodingHostTestFixture.response("follow-up-answer");
        };

        try (var agent = agent(workspace, model);
                CodingHostServer server = CodingHostServer.start(agent, "127.0.0.1", 0)) {
            Rpc rpc = new Rpc(server.port());
            rpc.initialize();
            JsonNode created =
                    rpc.result("session/create", params("text", "first task", "idempotencyKey", "offline-1"));
            String sessionId = created.path("session").path("sessionId").asText();
            String runId = created.path("activeRun").path("runId").asText();
            assertThat(firstInFlight.await(10, TimeUnit.SECONDS)).isTrue();
            rpc.result(
                    "run/followUp",
                    params(
                            "sessionId",
                            sessionId,
                            "runId",
                            runId,
                            "text",
                            "queued task",
                            "idempotencyKey",
                            "offline-follow"));
            releaseFirst.countDown();

            assertThat(followUpStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(firstRun.get()).isNotNull();
            assertThat(firstRun.get().value()).isEqualTo(runId);
            assertThat(followUpRun.get()).isNotNull();
            assertThat(followUpRun.get().value()).isNotEqualTo(firstRun.get().value());
        }
    }

    @Test
    void rejectsProtocolMismatchUnknownMethodsAndDisabledCapabilities() throws Exception {
        Path workspace = Files.createDirectory(directory.resolve("host-protocol"));
        try (var agent = agent(workspace, request -> CodingHostTestFixture.response("ok"));
                CodingHostServer server = CodingHostServer.start(agent, "127.0.0.1", 0)) {
            Rpc rpc = new Rpc(server.port());
            assertThat(rpc.error("session/list", params())
                            .path("data")
                            .path("reasonCode")
                            .asText())
                    .isEqualTo("CLIENT_CONTEXT_REQUIRED");
            JsonNode initialized = rpc.initialize();
            Rpc independent = new Rpc(server.port());
            independent.initialize();
            assertThat(independent
                            .result("session/list", params())
                            .path("sessions")
                            .size())
                    .isZero();
            assertThat(initialized.path("capabilities").path("preview").asBoolean())
                    .isFalse();
            assertThat(initialized.path("capabilities").path("session-consent").asBoolean())
                    .isFalse();
            JsonNode auth = rpc.result("auth/status", params());
            assertThat(auth.path("credentialWrite").asBoolean()).isFalse();
            assertThat(auth.path("credentialLogin").asBoolean()).isFalse();
            assertThat(auth.path("logout").asBoolean()).isTrue();
            assertThat(rpc.error("initialize", params("protocolVersion", "2"))
                            .path("data")
                            .path("reasonCode")
                            .asText())
                    .isEqualTo("CONTRACT_VERSION_UNSUPPORTED");
            assertThat(rpc.error("unknown/method", params())
                            .path("data")
                            .path("reasonCode")
                            .asText())
                    .isEqualTo("REMOTE_METHOD_UNSUPPORTED");
            assertThat(rpc.error("preview/subscribe", params("runId", "run-x"))
                            .path("data")
                            .path("reasonCode")
                            .asText())
                    .isEqualTo("REMOTE_METHOD_UNSUPPORTED");
            assertThat(rpc.error("approval/allowForSession", params())
                            .path("data")
                            .path("reasonCode")
                            .asText())
                    .isEqualTo("REMOTE_METHOD_UNSUPPORTED");
            assertThat(rpc.error("auth/saveApiKey", params("providerId", "openai"))
                            .path("data")
                            .path("reasonCode")
                            .asText())
                    .isEqualTo("REMOTE_METHOD_UNSUPPORTED");
            assertThat(rpc.error("session/open", params("sessionId", "missing"))
                            .path("data")
                            .path("reasonCode")
                            .asText())
                    .isEqualTo("SESSION_NOT_FOUND");
            assertThat(rpc.error("run/get", params("runId", "missing"))
                            .path("data")
                            .path("reasonCode")
                            .asText())
                    .isEqualTo("SESSION_NOT_FOUND");
            assertThat(rpc.error("session/history", params("sessionId", 7))
                            .path("data")
                            .path("reasonCode")
                            .asText())
                    .isEqualTo("INVALID_PARAMS");
            assertThat(rpc.error(
                                    "session/rename",
                                    params(
                                            "sessionId", "session-x",
                                            "displayName", "renamed",
                                            "expectedRevision", 1.5))
                            .path("data")
                            .path("reasonCode")
                            .asText())
                    .isEqualTo("INVALID_PARAMS");
            assertThatThrownBy(() -> CodingHostServer.loopback("0.0.0.0"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("HOST_BIND_MUST_BE_LOOPBACK");

            HttpRequest originRequest = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + server.port() + "/health"))
                    .header("Origin", "https://browser.example")
                    .GET()
                    .build();
            HttpResponse<String> originResponse =
                    HttpClient.newHttpClient().send(originRequest, HttpResponse.BodyHandlers.ofString());
            assertThat(originResponse.statusCode()).isEqualTo(403);
            assertThat(originResponse.body()).contains("BROWSER_ORIGIN_REJECTED");

            HttpRequest oversized = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/v1/rpc"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("x".repeat(257 * 1024)))
                    .build();
            HttpResponse<String> oversizedResponse =
                    HttpClient.newHttpClient().send(oversized, HttpResponse.BodyHandlers.ofString());
            assertThat(oversizedResponse.statusCode()).isEqualTo(413);
            assertThat(oversizedResponse.body()).contains("PAYLOAD_TOO_LARGE");

            HttpResponse<String> trailingResponse = post(
                    server.port(),
                    "application/json",
                    "{\"jsonrpc\":\"2.0\",\"id\":\"trailing\",\"method\":\"host/ping\"} {\"second\":true}");
            assertThat(trailingResponse.statusCode()).isEqualTo(200);
            assertThat(trailingResponse.body()).contains("PARSE_ERROR");

            HttpResponse<String> parameterizedResponse = post(
                    server.port(),
                    "application/json; charset=utf-8",
                    "{\"jsonrpc\":\"2.0\",\"id\":\"mime\",\"method\":\"host/ping\"}");
            assertThat(parameterizedResponse.statusCode()).isEqualTo(200);
            assertThat(parameterizedResponse.body()).contains("\"status\":\"ok\"");

            HttpResponse<String> subtypeResponse = post(
                    server.port(),
                    "application/json-patch+json",
                    "{\"jsonrpc\":\"2.0\",\"id\":\"mime-subtype\",\"method\":\"host/ping\"}");
            assertThat(subtypeResponse.statusCode()).isEqualTo(415);
            assertThat(subtypeResponse.body()).contains("UNSUPPORTED_MEDIA_TYPE");

            Path memoryConfiguration =
                    Files.writeString(directory.resolve("memory-host.yaml"), "persistence:\n  mode: memory\n");
            assertThatThrownBy(() -> CodingHostServer.start(
                            workspace, java.util.Optional.of(memoryConfiguration), "127.0.0.1", 0))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("HOST_DURABLE_PERSISTENCE_REQUIRED");
        }
    }

    private io.haifa.agent.cli.StandaloneCodingAgent agent(Path workspace, AgentChatModel model) {
        return StandaloneCodingAgents.open(
                workspace,
                CodingHostTestFixture.sqliteConfiguration(directory.resolve("host.db")),
                new PrintStream(new ByteArrayOutputStream()),
                model,
                ignored -> {},
                CodingHostTestFixture.protector());
    }

    private static void awaitTerminal(Rpc rpc, String runId) throws Exception {
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            JsonNode run = rpc.result("run/get", params("runId", runId));
            if (AgentRunStatus.valueOf(run.path("status").asText()).isTerminal()) return;
            Thread.sleep(20);
        }
        throw new AssertionError("run did not become terminal: " + runId);
    }

    private static HttpResponse<String> post(int port, String contentType, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/rpc"))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static List<String> historyBodies(JsonNode history) {
        return java.util.stream.StreamSupport.stream(history.path("items").spliterator(), false)
                .map(item -> item.path("body").asText())
                .toList();
    }

    private static void assertRunScopedEvents(JsonNode page, String runId, String sessionId) {
        assertThat(page.path("items").size()).isGreaterThan(0);
        for (JsonNode event : page.path("items")) {
            assertThat(event.path("runId").asText()).isEqualTo(runId);
            assertThat(event.path("sessionId").asText()).isEqualTo(sessionId);
        }
    }

    private static String readEventData(BufferedReader reader) {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data:"))
                    return line.substring("data:".length()).trim();
            }
            return "{}";
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    private static Map<String, Object> params(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }

    private static final class Rpc {
        private static final AtomicInteger IDS = new AtomicInteger();
        private final String endpoint;
        private final HttpClient http =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        private volatile String clientId;

        private Rpc(int port) {
            this.endpoint = "http://127.0.0.1:" + port;
        }

        JsonNode initialize() throws Exception {
            JsonNode result = result(
                    "initialize", params("protocolVersion", "1", "clientInfo", Map.of("name", "test", "version", "1")));
            clientId = result.path("clientId").asText();
            return result;
        }

        JsonNode result(String method, Map<String, Object> params) throws Exception {
            JsonNode response = call(method, params);
            assertThat(response.has("error")).as(response.toString()).isFalse();
            return response.path("result");
        }

        JsonNode error(String method, Map<String, Object> params) throws Exception {
            JsonNode response = call(method, params);
            assertThat(response.has("error")).as(response.toString()).isTrue();
            return response.path("error");
        }

        HttpResponse<InputStream> sse(String subscriptionId) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(
                            URI.create(endpoint + "/v1/events?subscriptionId=" + subscriptionId))
                    .timeout(Duration.ofSeconds(5))
                    .GET();
            if (clientId != null) builder.header("X-CA-Client-ID", clientId);
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        }

        private JsonNode call(String method, Map<String, Object> params) throws Exception {
            ObjectNode request = JSON.createObjectNode();
            request.put("jsonrpc", "2.0");
            request.put("id", "test-" + IDS.incrementAndGet());
            request.put("method", method);
            request.set("params", JSON.valueToTree(params));
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint + "/v1/rpc"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json");
            if (clientId != null) builder.header("X-CA-Client-ID", clientId);
            HttpRequest httpRequest = builder.POST(
                            HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(request)))
                    .build();
            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            return JSON.readTree(response.body());
        }
    }
}
