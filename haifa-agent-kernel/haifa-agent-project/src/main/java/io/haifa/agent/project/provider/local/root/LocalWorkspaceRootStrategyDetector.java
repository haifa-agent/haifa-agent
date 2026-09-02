package io.haifa.agent.project.provider.local.root;

import io.haifa.agent.project.root.WorkspaceRootStrategy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Pure Java detector that identifies if a host directory is a Git repository or Plain directory.
 */
public final class LocalWorkspaceRootStrategyDetector {

    public DetectionResult detect(Path directory) {
        Objects.requireNonNull(directory, "directory must not be null");
        Path normalized = directory.toAbsolutePath().normalize();
        if (!Files.exists(normalized) || !Files.isDirectory(normalized)) {
            return new DetectionResult(WorkspaceRootStrategy.PLAIN);
        }

        Path gitEntry = normalized.resolve(".git");
        if (Files.exists(gitEntry)) {
            if (Files.isDirectory(gitEntry)) {
                // Standard git repository
                Path headFile = gitEntry.resolve("HEAD");
                if (Files.exists(headFile)) {
                    return new DetectionResult(WorkspaceRootStrategy.GIT);
                }
            } else if (Files.isRegularFile(gitEntry)) {
                // Submodule or git worktree pointer (contains gitdir: ...)
                try {
                    String content = Files.readString(gitEntry).trim();
                    if (content.startsWith("gitdir:")) {
                        return new DetectionResult(WorkspaceRootStrategy.GIT);
                    }
                } catch (IOException ignored) {
                }
            }
        }

        return new DetectionResult(WorkspaceRootStrategy.PLAIN);
    }

    public record DetectionResult(WorkspaceRootStrategy strategy) {}
}
