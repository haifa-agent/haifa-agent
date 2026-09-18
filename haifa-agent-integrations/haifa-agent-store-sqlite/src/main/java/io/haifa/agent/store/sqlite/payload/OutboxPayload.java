package io.haifa.agent.store.sqlite.payload;

import java.util.Map;
import java.util.Objects;

public record OutboxPayload(Map<String, Object> values) {
    public OutboxPayload {
        values = Map.copyOf(Objects.requireNonNull(values, "values must not be null"));
    }
}
