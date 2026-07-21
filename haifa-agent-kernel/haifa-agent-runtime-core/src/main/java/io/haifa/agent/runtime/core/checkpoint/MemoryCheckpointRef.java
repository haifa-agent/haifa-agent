package io.haifa.agent.runtime.core.checkpoint;

import io.haifa.agent.memory.api.MemoryId;
import io.haifa.agent.memory.api.MemoryScope;
import io.haifa.agent.memory.api.MemoryVersion;
import java.util.Objects;

/** Reference-only memory selection persisted in a checkpoint; never contains Memory content. */
public record MemoryCheckpointRef(MemoryId id, MemoryVersion version, MemoryScope scope) {
    public MemoryCheckpointRef {
        id = Objects.requireNonNull(id);
        version = Objects.requireNonNull(version);
        scope = Objects.requireNonNull(scope);
    }
}
