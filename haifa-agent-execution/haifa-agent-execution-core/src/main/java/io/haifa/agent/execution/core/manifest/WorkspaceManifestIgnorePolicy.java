package io.haifa.agent.execution.core.manifest;

import io.haifa.agent.project.filesystem.FileMetadata;
import java.util.Objects;

/** Frozen policy describing paths intentionally excluded from execution change observation. */
public interface WorkspaceManifestIgnorePolicy {
    String version();

    boolean ignores(FileMetadata metadata);

    static WorkspaceManifestIgnorePolicy none(String version) {
        String frozenVersion = Objects.requireNonNull(version, "version must not be null");
        return new WorkspaceManifestIgnorePolicy() {
            @Override
            public String version() {
                return frozenVersion;
            }

            @Override
            public boolean ignores(FileMetadata metadata) {
                Objects.requireNonNull(metadata, "metadata must not be null");
                return false;
            }
        };
    }
}
