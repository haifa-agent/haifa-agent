package io.haifa.agent.project.hostworkspace;

import io.haifa.agent.project.hostworkspace.scope.AuthorizedHostDirectory;
import io.haifa.agent.project.path.WorkspacePath;
import java.nio.file.Path;
import java.util.Objects;

/** Short-lived host boundary value. It must not enter public DTOs, persistence, logs or Admin. */
public record LocatedRepository(AuthorizedHostDirectory directory, WorkspacePath workspaceRoot, Path hostRoot) {
    public LocatedRepository {
        directory = Objects.requireNonNull(directory, "directory must not be null");
        workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null");
        hostRoot = Objects.requireNonNull(hostRoot, "hostRoot must not be null")
                .toAbsolutePath()
                .normalize();
        if (!directory.encloses(hostRoot)) {
            throw new IllegalArgumentException("repository root is outside its authorized directory");
        }
        if (!workspaceRoot.workspaceId().equals(directory.workspaceId())) {
            throw new IllegalArgumentException("repository root workspace does not match its directory");
        }
    }
}
