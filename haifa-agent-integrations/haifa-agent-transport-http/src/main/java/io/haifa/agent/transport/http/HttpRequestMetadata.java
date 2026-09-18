package io.haifa.agent.transport.http;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Bounded HTTP metadata exposed to authentication without retaining a request body. */
public record HttpRequestMetadata(String method, String path, Map<String, List<String>> headers) {
    public HttpRequestMetadata {
        method =
                Objects.requireNonNull(method, "method must not be null").trim().toUpperCase(Locale.ROOT);
        path = Objects.requireNonNull(path, "path must not be null").trim();
        headers = Map.copyOf(Objects.requireNonNull(headers, "headers must not be null"));
    }

    public Optional<String> header(String name) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst()
                .map(String::trim)
                .filter(value -> !value.isEmpty());
    }
}
