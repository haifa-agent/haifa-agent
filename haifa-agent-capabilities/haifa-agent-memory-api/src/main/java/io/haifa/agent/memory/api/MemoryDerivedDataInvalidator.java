package io.haifa.agent.memory.api;

public interface MemoryDerivedDataInvalidator {
    void invalidate(MemoryRef memory, String reason);
}
