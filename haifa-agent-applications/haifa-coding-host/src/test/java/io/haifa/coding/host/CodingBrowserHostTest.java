package io.haifa.coding.host;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.haifa.coding.api.*;
import io.haifa.coding.api.CodingProtocol.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodingBrowserHostTest {
    @TempDir
    Path directory;

    private final HttpClient http =
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    private record Grant(String value, String clientId, String incarnation) {
        @Override
        public String toString() {
            return "Grant[redacted]";
        }
    }

    @Test
    void ingressRequiresLoopbackProofAndRealOriginWithoutEnablingNativeRoutes() throws Exception {
        String external = "https://coding.example.test:8443";
        String proof = "1".repeat(64);
        String header = "X-Haifa-Proxy-Proof";
        var ingress = new BrowserIngress(URI.create(external), proof);
        try (var fixture = new CodingHostFixture(directory);
                var host = fixture.start(Optional.of(new BrowserOptions(
                        fixture.assets,
                        "index.html",
                        Duration.ofMinutes(2),
                        Duration.ofMinutes(15),
                        Duration.ofSeconds(90),
                        4,
                        5,
                        Optional.of(ingress))))) {
            var info = host.endpoint().resolve("/browser/v1/info");
            assertThat(status(HttpRequest.newBuilder(info).header("X-Forwarded-Host", "coding.example.test")))
                    .isEqualTo(403);
            assertThat(status(HttpRequest.newBuilder(info).header(header, "2".repeat(64))))
                    .isEqualTo(403);
            assertThat(status(HttpRequest.newBuilder(info).header(header, proof).header(header, proof)))
                    .isEqualTo(403);
            assertThat(status(HttpRequest.newBuilder(info).header(header, proof)))
                    .isEqualTo(200);
            assertThat(status(HttpRequest.newBuilder(host.endpoint().resolve("/health"))
                            .header(header, proof)))
                    .isEqualTo(403);
            assertThat(status(HttpRequest.newBuilder(host.endpoint().resolve("/health"))))
                    .isEqualTo(200);

            var challenge = host.newPairingChallenge();
            var body = CodingHostFixture.JSON.writeValueAsBytes(
                    Map.of("challenge", challenge.value(), "hostIncarnation", challenge.hostIncarnation()));
            var pair = host.endpoint().resolve("/browser/v1/pair");
            for (String origin : List.of(host.endpoint().toString(), "null", "https://other.example.test")) {
                assertThat(status(HttpRequest.newBuilder(pair)
                                .header(header, proof)
                                .header("Origin", origin)
                                .header("X-Forwarded-Proto", "https")
                                .header("X-Forwarded-Host", "coding.example.test:8443")
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofByteArray(body))))
                        .isEqualTo(403);
            }
            assertThat(status(HttpRequest.newBuilder(pair)
                            .header(header, proof)
                            .header("Origin", external)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body))))
                    .isEqualTo(200);
        }
    }

    private int status(HttpRequest.Builder request) throws Exception {
        return http.send(request.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }

    @Test
    void symbolicLinkCannotExposeWorkspace() throws Exception {
        try (var fixture = new CodingHostFixture(directory)) {
            Files.writeString(fixture.workspace.resolve("private.txt"), "workspace-only");
            try {
                Files.createSymbolicLink(fixture.assets.resolve("leak.txt"), fixture.workspace.resolve("private.txt"));
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
                org.junit.jupiter.api.Assumptions.abort("Platform does not permit test symbolic links");
            }
            try (var host = fixture.start(Optional.of(new BrowserOptions(fixture.assets, "index.html")))) {
                assertThat(get(host, "/browser/leak.txt", null, null).statusCode())
                        .isEqualTo(404);
            }
        }
    }

    @Test
    void trustedStaticAssetsPairingAndSourceIsolationDoNotExposeWorkspaceOrAnotherContext() throws Exception {
        try (var fixture = new CodingHostFixture(directory);
                var host = fixture.start(Optional.of(new BrowserOptions(fixture.assets, "index.html")));
                var owner = host.embeddedClient()) {
            String origin = host.endpoint().toString();
            assertThat(get(host, "/browser/", null, null).statusCode()).isEqualTo(200);
            Files.writeString(fixture.workspace.resolve("private.txt"), "workspace-only");
            assertThat(get(host, "/browser/%2e%2e/workspace/private.txt", null, null)
                            .statusCode())
                    .isEqualTo(404);
            assertThat(get(host, "/browser/%252e%252e/private.txt", null, null).statusCode())
                    .isEqualTo(404);
            try (var wrongHost =
                    new Socket(host.endpoint().getHost(), host.endpoint().getPort())) {
                wrongHost
                        .getOutputStream()
                        .write(("GET /browser/v1/info HTTP/1.1\r\nHost: foreign.example\r\nConnection: close\r\n\r\n")
                                .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                assertThat(new java.io.BufferedReader(new java.io.InputStreamReader(wrongHost.getInputStream()))
                                .readLine())
                        .contains("403");
            }
            var challenge = host.newPairingChallenge();
            assertThat(post(
                                    host,
                                    "/browser/v1/pair",
                                    Map.of(
                                            "challenge",
                                            challenge.value(),
                                            "hostIncarnation",
                                            challenge.hostIncarnation()),
                                    null,
                                    null,
                                    null)
                            .statusCode())
                    .isEqualTo(403);
            var a = pair(host, challenge);
            var b = pair(host, host.newPairingChallenge());
            assertThat(post(
                                    host,
                                    "/browser/v1/pair",
                                    Map.of(
                                            "challenge",
                                            challenge.value(),
                                            "hostIncarnation",
                                            challenge.hostIncarnation()),
                                    null,
                                    origin,
                                    null)
                            .statusCode())
                    .isEqualTo(403);
            var ids = new HashSet<String>();
            for (int i = 0; i < 18; i++) {
                var initialized = result(rpc(host, a, "initialize", initialize(), origin, null));
                ids.add(initialized.path("clientId").asText());
                assertThat(initialized.path("optionalCapabilities").toString())
                        .doesNotContain("native-tool-details-v1");
            }
            assertThat(ids).containsExactly(a.clientId());
            result(rpc(host, b, "initialize", initialize(), origin, null));
            var logout = new Command(
                    "command",
                    "logout",
                    "browser-logout",
                    "browser-logout",
                    new CommandTarget(null, null, null, null, null),
                    null,
                    "fixture-connection",
                    null,
                    List.of());
            var rejected = result(rpc(
                    host,
                    a,
                    "product/command",
                    new CommandRequest(logout, new CommandOptions(false, false, List.of())),
                    origin,
                    null));
            assertThat(rejected.path("receipt").path("disposition").asText()).isEqualTo("rejected");
            assertThat(rejected.path("receipt").path("reasonCode").asText()).isEqualTo("CAPABILITY_UNSUPPORTED");
            var created = owner.execute(CodingHostProductTest.create("owner-create", "owner-create", "scope test"));
            String session = created.session().sessionId();
            var subscription = result(rpc(host, a, "presentation/subscribe", Map.of("sessionId", session), null, null));
            String id = subscription.path("subscriptionId").asText();
            assertThat(subscription
                            .path("snapshot")
                            .path("scope")
                            .path("source")
                            .asText())
                    .isEqualTo("browser");
            assertThat(rpc(host, a, "product/query", CodingHostProductTest.query("sessions", null, null), "null", null)
                            .statusCode())
                    .isEqualTo(403);
            assertThat(rpc(
                                    host,
                                    a,
                                    "product/query",
                                    CodingHostProductTest.query("sessions", null, null),
                                    "http://foreign.example",
                                    null)
                            .statusCode())
                    .isEqualTo(403);
            assertThat(rpc(
                                    host,
                                    a,
                                    "product/query",
                                    CodingHostProductTest.query("sessions", null, null),
                                    null,
                                    b.clientId())
                            .statusCode())
                    .isEqualTo(403);
            var wrong = new Grant("wrong-proof", a.clientId(), a.incarnation());
            assertThat(rpc(
                                    host,
                                    wrong,
                                    "product/query",
                                    CodingHostProductTest.query("sessions", null, null),
                                    null,
                                    null)
                            .statusCode())
                    .isEqualTo(403);
            assertThat(get(host, "/v1/events?subscriptionId=" + id, null, a.clientId())
                            .statusCode())
                    .isEqualTo(409);
            var cross = HttpRequest.newBuilder(host.endpoint().resolve("/browser/v1/events?subscriptionId=" + id))
                    .header("Authorization", "Bearer " + b.value())
                    .GET()
                    .build();
            assertThat(http.send(cross, HttpResponse.BodyHandlers.ofString()).statusCode())
                    .isEqualTo(403);
            assertThat(post(
                                    host,
                                    "/v1/rpc",
                                    Map.of(
                                            "jsonrpc",
                                            "2.0",
                                            "id",
                                            "native-bypass",
                                            "method",
                                            "product/query",
                                            "params",
                                            CodingHostProductTest.query("sessions", null, null)),
                                    null,
                                    null,
                                    a.clientId())
                            .statusCode())
                    .isEqualTo(409);
            assertThat(post(
                                    host,
                                    "/v1/rpc",
                                    Map.of(
                                            "jsonrpc",
                                            "2.0",
                                            "id",
                                            "origin",
                                            "method",
                                            "initialize",
                                            "params",
                                            Map.of("protocolVersion", "1")),
                                    null,
                                    origin,
                                    null)
                            .statusCode())
                    .isEqualTo(403);
            // A revoked owner cannot revive the grant or use a previously allocated browser client through native
            // routes.
            host.revokeBrowserClient(a.clientId());
            assertThat(rpc(host, a, "client/renew", Map.of(), null, null).statusCode())
                    .isEqualTo(403);
        }
    }

    @Test
    void establishedSseRevocationStopsObservationButAcceptedRunContinuesAndNewMutationIsRefused() throws Exception {
        lifecycle(false);
    }

    @Test
    void absoluteGrantExpiryActivelyTerminatesAnEstablishedSseWithoutCancellingTheRun() throws Exception {
        lifecycle(true);
    }

    private void lifecycle(boolean expiry) throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var fixture = new CodingHostFixture(directory);
                var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            fixture.answer.set(request -> {
                started.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS))
                        throw new IllegalStateException("controlled model was not released");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return CodingHostFixture.response("accepted-run-finished");
            });
            var options = new BrowserOptions(
                    fixture.assets,
                    "index.html",
                    Duration.ofMinutes(2),
                    expiry ? Duration.ofSeconds(3) : Duration.ofMinutes(1),
                    Duration.ofSeconds(30),
                    4,
                    5);
            try (var host = fixture.start(Optional.of(options));
                    var owner = host.embeddedClient()) {
                var created = owner.execute(
                        CodingHostProductTest.create("accepted-create", "accepted-key", "run remains owned"));
                String session = created.session().sessionId(),
                        run = created.receipt().runId();
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

                // Start the short grant lifetime after the independent Run has entered the model.
                var grant = pair(host, host.newPairingChallenge());
                result(rpc(
                        host, grant, "initialize", initialize(), host.endpoint().toString(), null));
                var sub = result(rpc(host, grant, "presentation/subscribe", Map.of("sessionId", session), null, null));
                var request = HttpRequest.newBuilder(host.endpoint()
                                .resolve("/browser/v1/events?subscriptionId="
                                        + sub.path("subscriptionId").asText()))
                        .header("Authorization", "Bearer " + grant.value())
                        .GET()
                        .build();
                var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                assertThat(response.statusCode()).isEqualTo(200);
                if (!expiry) {
                    var duplicate = http.send(request, HttpResponse.BodyHandlers.ofString());
                    assertThat(duplicate.statusCode()).isEqualTo(403);
                    var stillAttached = http.send(request, HttpResponse.BodyHandlers.ofString());
                    assertThat(CodingHostFixture.JSON
                                    .readTree(stillAttached.body())
                                    .path("error")
                                    .path("data")
                                    .path("reasonCode")
                                    .asText())
                            .isEqualTo("HOST_SUBSCRIPTION_ATTACHED");
                }
                var disconnected = workers.submit(() -> {
                    try (var input = response.body()) {
                        while (input.read() != -1) {}
                        return true;
                    } catch (java.io.IOException closed) {
                        return true;
                    }
                });
                if (!expiry) host.revokeBrowserClient(grant.clientId());
                assertThat(disconnected.get(8, TimeUnit.SECONDS)).isTrue();
                assertThat(owner.query(CodingHostProductTest.query("run", session, run))
                                .run()
                                .status())
                        .isEqualTo("RUNNING");
                int sessions = owner.query(CodingHostProductTest.query("sessions", null, null))
                        .sessions()
                        .size();
                var denied = rpc(
                        host,
                        grant,
                        "product/command",
                        new CommandRequest(
                                CodingHostProductTest.create("after-revoke", "after-revoke", "must never run"),
                                new CommandOptions(false, false, List.of())),
                        null,
                        null);
                assertThat(denied.statusCode()).isEqualTo(403);
                assertThat(owner.query(CodingHostProductTest.query("sessions", null, null))
                                .sessions())
                        .hasSize(sessions);
                release.countDown();
                try (var observation = owner.observe(session)) {
                    Snapshot current = observation.initial();
                    boolean committed = false;
                    for (int i = 0; i < 32; i++) {
                        if (current.entries().stream().anyMatch(e -> e.text().equals("accepted-run-finished"))) {
                            committed = true;
                            break;
                        }
                        var next = observation.poll(Duration.ofSeconds(5));
                        assertThat(next).isInstanceOf(Update.class);
                        current = CodingPresentationReducer.apply(current, (Update) next);
                    }
                    assertThat(committed).isTrue();
                }
            } finally {
                release.countDown();
            }
        }
    }

    private Grant pair(CodingHost host, PairingChallenge proof) throws Exception {
        var value = result(post(
                host,
                "/browser/v1/pair",
                Map.of("challenge", proof.value(), "hostIncarnation", proof.hostIncarnation()),
                null,
                host.endpoint().toString(),
                null));
        return new Grant(
                value.path("grant").asText(),
                value.path("clientId").asText(),
                value.path("hostIncarnation").asText());
    }

    private static Map<String, Object> initialize() {
        return Map.of(
                "protocolVersion",
                "1",
                "optionalCapabilities",
                List.of(
                        "coding-product-v1",
                        "session-presentation-v1",
                        "session-consent-v1",
                        "native-tool-details-v1"));
    }

    private HttpResponse<String> rpc(
            CodingHost host, Grant grant, String method, Object params, String origin, String client) throws Exception {
        return post(
                host,
                "/browser/v1/rpc",
                Map.of("jsonrpc", "2.0", "id", "probe", "method", method, "params", params),
                grant,
                origin,
                client);
    }

    private HttpResponse<String> post(
            CodingHost host, String path, Object body, Grant grant, String origin, String client) throws Exception {
        var request = HttpRequest.newBuilder(host.endpoint().resolve(path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(CodingHostFixture.JSON.writeValueAsBytes(body)));
        if (grant != null) request.header("Authorization", "Bearer " + grant.value());
        if (origin != null) request.header("Origin", origin);
        if (client != null) request.header("X-CA-Client-ID", client);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(CodingHost host, String path, String origin, String client) throws Exception {
        var request = HttpRequest.newBuilder(host.endpoint().resolve(path))
                .timeout(Duration.ofSeconds(10))
                .GET();
        if (origin != null) request.header("Origin", origin);
        if (client != null) request.header("X-CA-Client-ID", client);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode result(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(200);
        var value = CodingHostFixture.JSON.readTree(response.body());
        assertThat(value.has("error")).isFalse();
        return value.path("result");
    }
}
