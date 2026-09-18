package io.haifa.agent.transport.http;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Framework-neutral HTTP request used by a host-specific router/controller. */
public record HttpTransportRequest(
        String method, String path, Map<String, List<String>> headers, Map<String, List<String>> query, byte[] body) {
    public HttpTransportRequest {
        method =
                Objects.requireNonNull(method, "method must not be null").trim().toUpperCase(Locale.ROOT);
        path = Objects.requireNonNull(path, "path must not be null").trim();
        headers = immutable(headers, "headers");
        query = immutable(query, "query");
        body = Objects.requireNonNull(body, "body must not be null").clone();
    }

    public HttpRequestMetadata metadata() {
        return new HttpRequestMetadata(method, path, headers);
    }

    public Optional<String> header(String name) {
        return first(headers, name, true);
    }

    public Optional<String> query(String name) {
        return first(query, name, false);
    }

    @Override
    public byte[] body() {
        return body.clone();
    }

    private static Map<String, List<String>> immutable(Map<String, List<String>> source, String field) {
        Objects.requireNonNull(source, field + " must not be null");
        java.util.LinkedHashMap<String, List<String>> copy = new java.util.LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(Objects.requireNonNull(key), List.copyOf(value)));
        return Map.copyOf(copy);
    }

    private static Optional<String> first(Map<String, List<String>> values, String name, boolean ignoreCase) {
        return values.entrySet().stream()
                .filter(entry -> ignoreCase
                        ? entry.getKey().equalsIgnoreCase(name)
                        : entry.getKey().equals(name))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst()
                .map(String::trim)
                .filter(value -> !value.isEmpty());
    }
}
