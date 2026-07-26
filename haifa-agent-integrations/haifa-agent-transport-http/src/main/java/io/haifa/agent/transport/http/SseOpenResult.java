package io.haifa.agent.transport.http;

import java.util.Objects;
import java.util.Optional;

public record SseOpenResult(Optional<HttpSseSession> session, Optional<HttpTransportResponse> error) {
    public SseOpenResult {
        session = Objects.requireNonNull(session, "session must not be null");
        error = Objects.requireNonNull(error, "error must not be null");
        if (session.isPresent() == error.isPresent()) {
            throw new IllegalArgumentException("SSE open result must contain exactly one outcome");
        }
    }

    public static SseOpenResult opened(HttpSseSession session) {
        return new SseOpenResult(Optional.of(session), Optional.empty());
    }

    public static SseOpenResult failed(HttpTransportResponse response) {
        return new SseOpenResult(Optional.empty(), Optional.of(response));
    }
}
