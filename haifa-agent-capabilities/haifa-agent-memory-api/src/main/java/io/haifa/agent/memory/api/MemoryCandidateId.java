package io.haifa.agent.memory.api;

public record MemoryCandidateId(String value) {
    public MemoryCandidateId {
        value = MemoryValues.text(value, "value", 256);
    }
}
