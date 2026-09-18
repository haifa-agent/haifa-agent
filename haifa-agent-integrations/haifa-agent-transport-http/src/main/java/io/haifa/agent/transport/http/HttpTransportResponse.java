package io.haifa.agent.transport.http;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

public record HttpTransportResponse(int status, Map<String, String> headers, byte[] body) {
    public HttpTransportResponse {
        if (status < 100 || status > 599) throw new IllegalArgumentException("status must be a valid HTTP status");
        headers = Map.copyOf(Objects.requireNonNull(headers, "headers must not be null"));
        body = Objects.requireNonNull(body, "body must not be null").clone();
    }

    public String bodyUtf8() {
        return new String(body, StandardCharsets.UTF_8);
    }

    @Override
    public byte[] body() {
        return body.clone();
    }
}
