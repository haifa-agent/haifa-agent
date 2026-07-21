package io.haifa.agent.memory.api;

public record MemoryId(String value) {
    public MemoryId {
        value = MemoryValues.text(value, "value", 256);
    }
}
