package io.haifa.agent.memory.api;

public enum MemoryConflictResolution {
    KEEP_EXISTING,
    REPLACE_WITH_CANDIDATE,
    KEEP_BOTH,
    REJECT_CANDIDATE
}
