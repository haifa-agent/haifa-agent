package io.haifa.agent.common.version;

import java.util.Objects;

/** Stable major/minor schema version used by persisted data and external contracts. */
public record SchemaVersion(int major, int minor) implements Comparable<SchemaVersion> {

    public static final SchemaVersion V1 = new SchemaVersion(1, 0);

    public SchemaVersion {
        if (major < 1) {
            throw new IllegalArgumentException("schema major version must be positive");
        }
        if (minor < 0) {
            throw new IllegalArgumentException("schema minor version must not be negative");
        }
    }

    public SchemaVersion(int major) {
        this(major, 0);
    }

    public static SchemaVersion parse(String value) {
        String normalized =
                Objects.requireNonNull(value, "value must not be null").trim();
        String[] parts = normalized.split("\\.", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("schema version must use major.minor format");
        }
        try {
            return new SchemaVersion(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("schema version must contain numeric components", exception);
        }
    }

    @Override
    public int compareTo(SchemaVersion other) {
        int majorComparison = Integer.compare(major, other.major);
        return majorComparison != 0 ? majorComparison : Integer.compare(minor, other.minor);
    }

    @Override
    public String toString() {
        return major + "." + minor;
    }
}
