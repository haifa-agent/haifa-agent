package io.haifa.agent.sdk.memory;

import io.haifa.agent.memory.api.MemoryRef;
import java.util.Objects;

public record InvalidateMemoryCommand(MemoryRef memory, String idempotencyKey, String reason) {
    public InvalidateMemoryCommand {
        memory = Objects.requireNonNull(memory, "memory must not be null");
        idempotencyKey = MemoryScopeSpec.requireText(idempotencyKey, 256);
        reason = MemoryScopeSpec.requireText(reason, 128);
    }
}
