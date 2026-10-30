package io.haifa.agent.auth.localmodel.opencode;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Loopback console stub shared by this module's driver tests; no external network is used. */
final class StubConsole implements AutoCloseable {
    interface Handler {
        void handle(String path, HttpExchange exchange) throws IOException;
    }

    private final HttpServer server;
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());

    private StubConsole(HttpServer server) {
        this.server = server;
    }

    static StubConsole start(Handler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        StubConsole stub = new StubConsole(server);
        server.createContext("/", exchange -> {
            try {
                String body = readBody(exchange);
                stub.requests.add(exchange.getRequestMethod() + " "
                        + exchange.getRequestURI().getPath() + " " + body);
                handler.handle(exchange.getRequestURI().getPath(), exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
        return stub;
    }

    OpenCodeGoEndpoints endpoints() {
        return new OpenCodeGoEndpoints(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/console"));
    }

    URI verificationUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/console/device");
    }

    List<String> requests() {
        return List.copyOf(requests);
    }

    static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody()) {
            return new String(input.readNBytes(4096), StandardCharsets.UTF_8);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
