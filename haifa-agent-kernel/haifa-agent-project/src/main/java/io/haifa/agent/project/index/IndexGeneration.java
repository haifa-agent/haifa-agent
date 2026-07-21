package io.haifa.agent.project.index;

public record IndexGeneration(long value) implements Comparable<IndexGeneration> {
    public IndexGeneration {
        if (value < 1) throw new IllegalArgumentException("generation must be positive");
    }

    public IndexGeneration next() {
        return new IndexGeneration(Math.addExact(value, 1));
    }

    @Override
    public int compareTo(IndexGeneration other) {
        return Long.compare(value, other.value);
    }
}
