package io.haifa.coding.host;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;

/** Shared local HTTP Stub plus public Host options; no CLI/private assembly hooks or real Provider. */
final class CodingHostFixture implements AutoCloseable {
    static final ObjectMapper JSON = new ObjectMapper();
    final Path root, workspace, assets, config;
    final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    final HttpServer model;
    final AtomicInteger modelCalls = new AtomicInteger();
    final java.util.concurrent.atomic.AtomicReference<Function<JsonNode, JsonNode>> answer =
            new java.util.concurrent.atomic.AtomicReference<>(ignored -> response("sdk-safe-answer"));
    final Map<String, String> environment =
            Map.of("HFA_FIXTURE_KEY", "hfa-fixture-key-NOT-REAL-0000", "HAIFA_ALLOW_INSECURE_LOOPBACK_MODEL", "true");
    final List<CodingHost> hosts = new ArrayList<>();

    CodingHostFixture(Path root) throws Exception {
        this.root = root;
        workspace = Files.createDirectories(root.resolve("workspace"));
        assets = Files.createDirectories(root.resolve("assets"));
        config = root.resolve("trusted.yaml");
        Files.writeString(
                assets.resolve("index.html"),
                "<!doctype html><title>Protocol probe</title><script src=probe.js></script>");
        Files.writeString(assets.resolve("probe.js"), "window.probeReady=true;");
        model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        model.setExecutor(executor);
        model.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                var request = JSON.readTree(exchange.getRequestBody().readNBytes(1024 * 1024));
                modelCalls.incrementAndGet();
                byte[] bytes = JSON.writeValueAsBytes(answer.get().apply(request));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        model.start();
        Files.writeString(
                config,
                """
models:
  default: sdk-fixture
  providers:
    - id: deepseek
      displayName: SDK Stub
      endpoint: http://127.0.0.1:%d/v1
      credentialRef: env://HFA_FIXTURE_KEY
      nativeStreaming: false
      apiBindings:
        - style: openai-chat-completions
          dialect: deepseek-openai-chat
      models:
        - id: sdk-fixture
          displayName: SDK Stub Model
          providerModelId: deepseek-flash
          style: openai-chat-completions
          capabilities: [TEXT_CHAT, TOOL_CALLING]
          contextWindow: 32768
          maxOutputTokens: 1024
tools:
  enabled: [file_stat, file_read, file_create, file_write, execution_run, ask_clarification]
approval:
  mode: ask
  threshold: low
persistence:
  mode: SQLITE
  databasePath: '%s'
  protection: NONE
execution:
  provider: host-guarded
  shell: auto
"""
                        .formatted(
                                model.getAddress().getPort(),
                                root.resolve("unused-owner-path.sqlite")
                                        .toAbsolutePath()
                                        .toString()
                                        .replace("'", "''")));
    }

    HostOptions options(Optional<BrowserOptions> browser) {
        return new HostOptions(
                workspace,
                root.resolve("data"),
                Optional.of(config),
                "127.0.0.1",
                0,
                "Third-party SDK product",
                browser);
    }

    CodingHost start(Optional<BrowserOptions> browser) {
        var host = CodingHosts.start(options(browser), environment);
        hosts.add(host);
        return host;
    }

    static JsonNode response(String text) {
        return JSON.valueToTree(Map.of(
                "id",
                "stub",
                "object",
                "chat.completion",
                "created",
                0,
                "model",
                "deepseek-flash",
                "choices",
                List.of(Map.of(
                        "index", 0, "message", Map.of("role", "assistant", "content", text), "finish_reason", "stop")),
                "usage",
                Map.of("prompt_tokens", 1, "completion_tokens", 1, "total_tokens", 2)));
    }

    static JsonNode tool(String id, String name, Map<String, Object> args) {
        var message = new LinkedHashMap<String, Object>();
        message.put("role", "assistant");
        message.put("content", null);
        try {
            message.put(
                    "tool_calls",
                    List.of(Map.of(
                            "id",
                            id,
                            "type",
                            "function",
                            "function",
                            Map.of("name", name, "arguments", JSON.writeValueAsString(args)))));
        } catch (java.io.IOException invalid) {
            throw new IllegalStateException(invalid);
        }
        return JSON.valueToTree(Map.of(
                "id",
                "stub",
                "object",
                "chat.completion",
                "created",
                0,
                "model",
                "deepseek-flash",
                "choices",
                List.of(Map.of("index", 0, "message", message, "finish_reason", "tool_calls")),
                "usage",
                Map.of("prompt_tokens", 1, "completion_tokens", 1, "total_tokens", 2)));
    }

    public void close() {
        hosts.forEach(CodingHost::close);
        model.stop(0);
        executor.shutdownNow();
    }
}
