package io.haifa.agent.project.hostworkspace.registry;

import java.util.Objects;

/** Path-redacted product/model projection of one registered local workspace root. */
public record HostWorkspaceRegistryView(
        String workspaceRef,
        String safeDisplayName,
        HostWorkspaceRegistrySource source,
        HostWorkspaceRegistryStatus status) {

    public HostWorkspaceRegistryView {
        workspaceRef = required(workspaceRef, "workspaceRef");
        safeDisplayName = required(safeDisplayName, "safeDisplayName");
        source = Objects.requireNonNull(source, "source must not be null");
        status = Objects.requireNonNull(status, "status must not be null");
    }

    private static String required(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
