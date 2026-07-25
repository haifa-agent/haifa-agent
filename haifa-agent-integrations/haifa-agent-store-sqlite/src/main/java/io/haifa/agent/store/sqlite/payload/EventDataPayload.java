package io.haifa.agent.store.sqlite.payload;

import java.util.Map;
import java.util.Objects;

public record EventDataPayload(Map<String, Object> values) {
    public EventDataPayload {
        values = Map.copyOf(Objects.requireNonNull(values, "values must not be null"));
    }
}
