package io.haifa.agent.memory.api;

public record MemoryVersion(long value) implements Comparable<MemoryVersion> {
    public MemoryVersion {
        if (value < 1) throw new IllegalArgumentException("value must be positive");
    }

    @Override
    public int compareTo(MemoryVersion other) {
        return Long.compare(value, other.value);
    }
}
