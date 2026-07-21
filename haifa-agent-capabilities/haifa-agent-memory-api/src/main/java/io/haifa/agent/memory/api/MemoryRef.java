package io.haifa.agent.memory.api;

import java.util.Objects;

public record MemoryRef(MemoryId id, MemoryVersion version) {
    public MemoryRef {
        id = Objects.requireNonNull(id, "id must not be null");
        version = Objects.requireNonNull(version, "version must not be null");
    }
}
