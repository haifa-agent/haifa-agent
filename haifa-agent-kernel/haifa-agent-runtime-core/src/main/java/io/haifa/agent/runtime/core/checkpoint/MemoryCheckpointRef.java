package io.haifa.agent.runtime.core.checkpoint;

import io.haifa.agent.memory.api.MemoryId;
import io.haifa.agent.memory.api.MemoryScope;
import java.util.Objects;

/** Reference-only memory selection persisted in a checkpoint; never contains Memory content. */
public record MemoryCheckpointRef(MemoryId id, long revision, MemoryScope scope) {
    public MemoryCheckpointRef {
        id = Objects.requireNonNull(id);
        if (revision < 1) throw new IllegalArgumentException("revision must be positive");
        scope = Objects.requireNonNull(scope);
    }
}
