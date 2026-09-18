package io.haifa.agent.transport.http;

import java.util.Objects;
import java.util.Optional;

public record SseFrame(Optional<String> id, Optional<String> event, Optional<String> data, Optional<String> comment) {
    public SseFrame {
        id = clean(id, "id");
        event = clean(event, "event");
        data = Objects.requireNonNull(data, "data must not be null");
        comment = clean(comment, "comment");
        if (comment.isPresent() == data.isPresent()) {
            throw new IllegalArgumentException("SSE frame must be either an event or a comment");
        }
        if (data.isPresent() && (id.isEmpty() || event.isEmpty())) {
            throw new IllegalArgumentException("event frames require id and event");
        }
    }

    public static SseFrame event(String id, String event, String data) {
        return new SseFrame(Optional.of(id), Optional.of(event), Optional.of(data), Optional.empty());
    }

    public static SseFrame heartbeat() {
        return new SseFrame(Optional.empty(), Optional.empty(), Optional.empty(), Optional.of("heartbeat"));
    }

    public String encode() {
        if (comment.isPresent()) return ": " + comment.orElseThrow() + "\n\n";
        StringBuilder encoded = new StringBuilder();
        encoded.append("id: ").append(id.orElseThrow()).append('\n');
        encoded.append("event: ").append(event.orElseThrow()).append('\n');
        data.orElseThrow()
                .lines()
                .forEach(line -> encoded.append("data: ").append(line).append('\n'));
        return encoded.append('\n').toString();
    }

    private static Optional<String> clean(Optional<String> value, String field) {
        return Objects.requireNonNull(value, field + " must not be null").map(item -> {
            String normalized = item.trim();
            if (normalized.isEmpty() || normalized.indexOf('\r') >= 0 || normalized.indexOf('\n') >= 0) {
                throw new IllegalArgumentException(field + " must be a single non-blank line");
            }
            return normalized;
        });
    }
}
