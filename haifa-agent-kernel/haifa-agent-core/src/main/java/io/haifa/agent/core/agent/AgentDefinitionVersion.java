package io.haifa.agent.core.agent;

/** Semantic version of a frozen Agent definition. */
public record AgentDefinitionVersion(int major, int minor, int patch) implements Comparable<AgentDefinitionVersion> {

    public AgentDefinitionVersion {
        if (major < 1 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("definition version must be at least 1.0.0");
        }
    }

    @Override
    public int compareTo(AgentDefinitionVersion other) {
        int result = Integer.compare(major, other.major);
        if (result == 0) {
            result = Integer.compare(minor, other.minor);
        }
        return result == 0 ? Integer.compare(patch, other.patch) : result;
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
