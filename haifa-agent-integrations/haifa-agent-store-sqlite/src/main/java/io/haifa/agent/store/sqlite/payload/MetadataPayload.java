package io.haifa.agent.store.sqlite.payload;

import java.util.Map;
import java.util.Objects;

/** Explicit payload envelope for open, persistence-safe metadata. */
public record MetadataPayload(Map<String, Object> values) {
    public MetadataPayload {
        values = Map.copyOf(Objects.requireNonNull(values, "values must not be null"));
    }
}
