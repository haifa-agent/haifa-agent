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
}
