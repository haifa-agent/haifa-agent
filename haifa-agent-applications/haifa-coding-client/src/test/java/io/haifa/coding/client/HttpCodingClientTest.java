package io.haifa.coding.client;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.haifa.coding.api.*;
import io.haifa.coding.api.CodingProtocol.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HttpCodingClientTest {
    @Test
    void atomicallyConsumesCrLfFramesAndDeduplicatesWithoutInventingRevisionGaps() throws Exception {
        try (var host = new WireHost()) {
            ObjectNode first = host.update(0, 1), second = host.update(1, 2);
            host.frames = List.of(frame(first), frame(first), frame(second));
            try (var client = CodingClients.connect(host.endpoint());
                    var observation = client.observe(host.session())) {
                assertThat(((Update) observation.poll(Duration.ofSeconds(5))).revision())
                        .isEqualTo(1);
                assertThat(((Update) observation.poll(Duration.ofSeconds(5))).revision())
                        .isEqualTo(2);
                assertThat(observation.poll(Duration.ZERO)).isNull();
            }
        }
    }

    @Test
    void foreignScopeUnknownCriticalContentAndUtf8OverflowRequireResyncBeforeAnyPartialApplication() throws Exception {
        for (String kind :
                List.of("foreign-scope", "unknown-critical", "utf8-overflow", "malformed-utf8", "wrong-channel")) {
            try (var host = new WireHost()) {
                ObjectNode update = host.update(0, 1);
                if (kind.equals("foreign-scope"))
                    ((ObjectNode) update.path("replacement").path("scope")).put("clientId", "foreign-client");
                if (kind.equals("unknown-critical"))
                    ((ObjectNode) update.path("replacement").path("entries").get(0))
                            .put("kind", "unknown-critical-entry");
                if (kind.equals("utf8-overflow"))
                    ((ObjectNode) update.path("replacement").path("entries").get(0)).put("text", "汉".repeat(22000));
                byte[] frame = kind.equals("malformed-utf8") ? new byte[] {(byte) 255, 10, 10} : frame(update);
                if (kind.equals("wrong-channel"))
                    frame = new String(frame, StandardCharsets.UTF_8)
                            .replace("event: presentation", "event: tool-preview")
                            .getBytes(StandardCharsets.UTF_8);
                host.frames = List.of(frame);
                try (var client = CodingClients.connect(host.endpoint());
                        var observation = client.observe(host.session())) {
                    assertThat(observation.poll(Duration.ofSeconds(5))).as(kind).isInstanceOf(Resync.class);
                    assertThat(observation.closed()).isTrue();
                    assertThat(observation.initial().revision()).isZero();
                }
            }
        }
    }

    @Test
    void previewUsesTheSameSafeProductPayloadAndHasIndependentObservationOwnership() throws Exception {
        try (var host = new WireHost()) {
            var payload = Map.of(
                    "sessionId",
                    host.session(),
                    "runId",
                    "preview-run",
                    "toolCallId",
                    "tool-call",
                    "channel",
                    "STDOUT",
                    "text",
                    "safe preview",
                    "outputTruncated",
                    false,
                    "previewDropped",
                    false);
            host.frames =
                    List.of(("event: tool-preview\r\ndata: " + WireHost.JSON.writeValueAsString(payload) + "\r\n\r\n")
                            .getBytes(StandardCharsets.UTF_8));
            try (var client = CodingClients.connect(host.endpoint());
                    var preview = client.preview(host.session(), "preview-run")) {
                var frame = preview.poll(Duration.ofSeconds(5));
                assertThat(frame.text()).isEqualTo("safe preview");
                assertThat(frame.toolCallId()).isEqualTo("tool-call");
                assertThat(preview.unavailableReason()).isNull();
            }
        }
    }

    @Test
    void aLostMutationReplyStaysUnknownAndIsNeverSentTwice() throws Exception {
        try (var host = new WireHost();
                var client = CodingClients.connect(host.endpoint())) {
            host.dropMutation = true;
            var command = new Command(
                    "command",
                    "create",
                    "request-once",
                    "domain-key",
                    new CommandTarget(null, null, null, null, null),
                    "synthetic input",
                    null,
                    null,
                    List.of());
            assertThat(client.execute(command).receipt().disposition()).isEqualTo("unknown");
            assertThat(host.mutations).hasValue(1);
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(10)
    void stalledMutationBodyHasABoundedUnknownOutcomeWithoutReplay() throws Exception {
        try (var host = new WireHost();
                var client = CodingClients.connect(
                        new ClientOptions(host.endpoint(), Duration.ofSeconds(1), "bounded-client", "1"))) {
            host.stallMutation = true;
            var command = new Command(
                    "command",
                    "create",
                    "stalled-once",
                    "stalled-key",
                    new CommandTarget(null, null, null, null, null),
                    "synthetic input",
                    null,
                    null,
                    List.of());
            assertThat(client.execute(command).receipt().disposition()).isEqualTo("unknown");
            assertThat(host.mutations).hasValue(1);
        }
    }

    @Test
    void oldHostWithoutTheProductCapabilityIsExplicitlyRefused() throws Exception {
        try (var host = new WireHost()) {
            host.legacy = true;
            assertThatThrownBy(() -> CodingClients.connect(host.endpoint()))
                    .isInstanceOfSatisfying(CodingClientException.class, e -> assertThat(e.code())
                            .isEqualTo("PRODUCT_CAPABILITY_UNSUPPORTED"));
            assertThat(host.mutations).hasValue(0);
        }
    }

    private static byte[] frame(JsonNode data) throws Exception {
        return ("event: presentation\r\ndata: " + WireHost.JSON.writeValueAsString(data) + "\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static final class WireHost implements AutoCloseable {
        static final ObjectMapper JSON = new ObjectMapper();
        final HttpServer server;
        final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger mutations = new AtomicInteger();
        final ObjectNode snapshot = (ObjectNode) JSON.readTree(
                Path.of("../haifa-coding-api/contract/fixtures/snapshot.json").toFile());
        volatile List<byte[]> frames = List.of();
        volatile boolean dropMutation, stallMutation, legacy;

        WireHost() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/v1/rpc", exchange -> {
                try (exchange) {
                    var request = JSON.readTree(exchange.getRequestBody());
                    String method = request.path("method").asText();
                    Object value;
                    if (method.equals("initialize"))
                        value = Map.of(
                                "protocolVersion",
                                "1",
                                "clientId",
                                snapshot.path("scope").path("clientId").asText(),
                                "hostIncarnation",
                                snapshot.path("scope").path("hostIncarnation").asText(),
                                "projectId",
                                "wire-project",
                                "displayName",
                                "wire fixture",
                                "optionalCapabilities",
                                legacy
                                        ? List.of("session-consent-v1")
                                        : List.of(
                                                "coding-product-v1",
                                                "session-presentation-v1",
                                                "session-consent-v1",
                                                "preview-v1"));
                    else if ((method.equals("presentation/subscribe") || method.equals("preview/subscribe")))
                        value = Map.of("subscriptionId", "subscription", "snapshot", snapshot);
                    else if (method.equals("product/command")) {
                        mutations.incrementAndGet();
                        if (dropMutation) return;
                        if (stallMutation) {
                            exchange.sendResponseHeaders(200, 0);
                            exchange.getResponseBody().write('{');
                            exchange.getResponseBody().flush();
                            try {
                                release.await();
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            }
                            return;
                        }
                        value = Map.of();
                    } else value = Map.of("closed", true, "renewed", true);
                    var reply = new LinkedHashMap<String, Object>();
                    reply.put("jsonrpc", "2.0");
                    reply.put("id", request.get("id"));
                    reply.put("result", value);
                    byte[] bytes = JSON.writeValueAsBytes(reply);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            server.createContext("/v1/events", exchange -> {
                try (exchange) {
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    for (var frame : frames) {
                        exchange.getResponseBody().write(frame);
                        exchange.getResponseBody().flush();
                    }
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            server.start();
        }

        String session() {
            return snapshot.path("scope").path("sessionId").asText();
        }

        URI endpoint() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        ObjectNode update(int base, int revision) {
            var result = JSON.createObjectNode();
            result.put("kind", "update");
            result.put("viewId", snapshot.path("scope").path("viewId").asText());
            result.put("baseRevision", base);
            result.put("revision", revision);
            var replacement = snapshot.deepCopy();
            replacement.put("revision", revision);
            result.set("replacement", replacement);
            return result;
        }

        public void close() {
            release.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
