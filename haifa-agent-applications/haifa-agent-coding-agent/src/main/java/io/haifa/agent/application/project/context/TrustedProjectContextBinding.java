package io.haifa.agent.application.project.context;

import io.haifa.agent.project.workspace.WorkspaceId;
import java.util.Objects;
import java.util.Set;

public record TrustedProjectContextBinding(
        WorkspaceId workspaceId, Set<String> capabilities, Set<String> sourceIds, String configurationDigest) {
    public TrustedProjectContextBinding {
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities must not be null"));
        sourceIds = Set.copyOf(Objects.requireNonNull(sourceIds, "sourceIds must not be null"));
        configurationDigest = Objects.requireNonNull(configurationDigest, "configurationDigest must not be null")
                .trim();
        if (configurationDigest.isEmpty()) throw new IllegalArgumentException("configurationDigest must not be blank");
    }
}
