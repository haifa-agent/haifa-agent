package io.haifa.agent.project.provider.local.root;

import io.haifa.agent.project.root.WorkspaceRootAlias;
import io.haifa.agent.project.root.WorkspaceRootPermission;
import io.haifa.agent.project.root.WorkspaceRootStrategy;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Concrete local workspace root backed by a host filesystem directory.
 */
public record LocalWorkspaceRoot(
        WorkspaceRootAlias alias, Path hostPath, WorkspaceRootPermission permission, WorkspaceRootStrategy strategy) {

    public LocalWorkspaceRoot {
        Objects.requireNonNull(alias, "alias must not be null");
        Objects.requireNonNull(hostPath, "hostPath must not be null");
        Objects.requireNonNull(permission, "permission must not be null");
        Objects.requireNonNull(strategy, "strategy must not be null");
        if (!hostPath.isAbsolute()) {
            throw new IllegalArgumentException("hostPath must be absolute: " + hostPath);
        }
        hostPath = hostPath.normalize();
    }

    public static LocalWorkspaceRoot of(
            WorkspaceRootAlias alias,
            Path hostPath,
            WorkspaceRootPermission permission,
            WorkspaceRootStrategy strategy) {
        return new LocalWorkspaceRoot(alias, hostPath, permission, strategy);
    }

    public static LocalWorkspaceRoot main(Path hostPath, WorkspaceRootStrategy strategy) {
        return new LocalWorkspaceRoot(WorkspaceRootAlias.MAIN, hostPath, WorkspaceRootPermission.READ_WRITE, strategy);
    }
}
