package io.haifa.agent.memory.api;

import java.util.Objects;

/** Minimal provenance: which Run, message, tool call or user command produced the Memory. */
public record MemorySourceRef(MemorySourceType type, String sourceId) {
    public MemorySourceRef {
        type = Objects.requireNonNull(type, "type must not be null");
        sourceId = MemoryValues.text(sourceId, "sourceId", 512);
    }
}
