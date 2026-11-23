package io.haifa.agent.sdk.contribution;

import io.haifa.agent.memory.api.MemoryId;
import io.haifa.agent.memory.api.MemoryOperationException;
import java.util.function.Supplier;

/** Commits an instruction's memory mutation together with its owner-scoped receipt. */
@FunctionalInterface
public interface MemoryCommandExecutor {
    /**
     * On replay, returns the original fact ID even if the fact has since been corrected or deleted.
     * A different payload for the same logical operation must be rejected.
     */
    MemoryId apply(String ownerKey, String operationKey, String payloadDigest, Supplier<MemoryId> mutation);

    static MemoryCommandExecutor unsupported() {
        return (ownerKey, operationKey, payloadDigest, mutation) -> {
            throw new MemoryOperationException("MEMORY_COMMAND_UNSUPPORTED");
        };
    }
}
