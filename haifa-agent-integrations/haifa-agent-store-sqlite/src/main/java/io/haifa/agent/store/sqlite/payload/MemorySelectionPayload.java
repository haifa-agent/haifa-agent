package io.haifa.agent.store.sqlite.payload;

import io.haifa.agent.runtime.core.checkpoint.MemoryCheckpointRef;
import java.util.List;

public record MemorySelectionPayload(List<MemoryCheckpointRef> memories) {
    public MemorySelectionPayload {
        memories = List.copyOf(memories);
    }
}
