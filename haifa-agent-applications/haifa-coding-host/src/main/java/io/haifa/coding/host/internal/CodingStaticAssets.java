package io.haifa.coding.host.internal;

import com.sun.net.httpserver.HttpExchange;
import io.haifa.coding.host.BrowserOptions;
import java.io.IOException;
import java.nio.file.*;
import java.util.Map;

/** Only explicitly trusted immutable build assets are reachable, never arbitrary workspace files. */
final class CodingStaticAssets {
    private final Path root;
    private final String entry;

    CodingStaticAssets(BrowserOptions options) throws IOException {
        root = options.assetRoot().toRealPath();
        entry = options.entryPoint();
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("Invalid asset root");
        resolve(entry);
    }

    private Path resolve(String relative) throws IOException {
        if (relative.contains("\\") || relative.contains("%") || relative.indexOf(0) >= 0)
            throw new IOException("Invalid asset path");
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root)) throw new IOException("Invalid asset path");
        for (Path part : root.relativize(path)) {
            if (part.toString().equals("..")) throw new IOException("Invalid asset path");
        }
        if (!path.toRealPath().startsWith(root) || !Files.isRegularFile(path) || Files.size(path) > 8 * 1024 * 1024)
            throw new IOException("Asset unavailable");
        return path;
    }

    void serve(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!exchange.getRequestMethod().equals("GET")
                    && !exchange.getRequestMethod().equals("HEAD")) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (!path.startsWith("/browser/")
                    || path.startsWith("/browser/v1/")
                    || path.contains("/../")
                    || path.contains("/./")) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            Path file;
            try {
                file = resolve(path.equals("/browser/") ? entry : path.substring("/browser/".length()));
            } catch (IOException invalid) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            String name = file.getFileName().toString();
            String extension = name.substring(Math.max(0, name.lastIndexOf('.') + 1));
            String type = Map.of(
                            "html",
                            "text/html; charset=utf-8",
                            "js",
                            "text/javascript; charset=utf-8",
                            "mjs",
                            "text/javascript; charset=utf-8",
                            "css",
                            "text/css; charset=utf-8",
                            "svg",
                            "image/svg+xml",
                            "png",
                            "image/png",
                            "ico",
                            "image/x-icon",
                            "json",
                            "application/json")
                    .getOrDefault(extension, "application/octet-stream");
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders()
                    .set(
                            "Content-Security-Policy",
                            "default-src 'self'; connect-src 'self'; script-src 'self'; style-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
            if (exchange.getRequestMethod().equals("HEAD")) {
                exchange.getResponseHeaders().set("Content-Length", Long.toString(Files.size(file)));
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            byte[] bytes = Files.readAllBytes(file);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }
}
