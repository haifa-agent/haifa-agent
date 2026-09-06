package io.haifa.agent.project.workspace;

import java.util.Objects;
import java.util.Set;

public record WorkspaceCapabilitySet(Set<String> values) {
    public WorkspaceCapabilitySet {
        values = Objects.requireNonNull(values, "values must not be null").stream()
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public static WorkspaceCapabilitySet readOnlyFiles() {
        return new WorkspaceCapabilitySet(Set.of("files.list", "files.stat", "files.read", "files.search"));
    }

    public static WorkspaceCapabilitySet readWriteFiles() {
        return new WorkspaceCapabilitySet(
                Set.of("files.list", "files.stat", "files.read", "files.search", "files.write", "files.delete"));
    }

    public static WorkspaceCapabilitySet executionFiles() {
        java.util.HashSet<String> capabilities =
                new java.util.HashSet<>(readWriteFiles().values());
        capabilities.add("execution.run");
        capabilities.add("git.read");
        return new WorkspaceCapabilitySet(capabilities);
    }

    public boolean allows(String capability) {
        return values.contains(Objects.requireNonNull(capability, "capability must not be null"));
    }
}
