package io.haifa.agent.artifact;

public record ArtifactVersion(long value) implements Comparable<ArtifactVersion> {
    public ArtifactVersion {
        if (value < 1) throw new IllegalArgumentException("version must be positive");
    }

    @Override
    public int compareTo(ArtifactVersion other) {
        return Long.compare(value, other.value);
    }
}
