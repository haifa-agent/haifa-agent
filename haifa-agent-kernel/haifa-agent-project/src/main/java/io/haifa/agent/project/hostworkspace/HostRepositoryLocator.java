package io.haifa.agent.project.hostworkspace;

import io.haifa.agent.project.hostworkspace.scope.AuthorizedHostDirectory;
import io.haifa.agent.project.hostworkspace.scope.ResolvedAuthorizedPath;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Locates the nearest valid worktree without ever walking above the authorized host boundary. */
public final class HostRepositoryLocator {
    private final HostGitInspectionPort git;
    private final ConcurrentHashMap<CacheKey, Optional<LocatedRepository>> cache = new ConcurrentHashMap<>();

    public HostRepositoryLocator(HostGitInspectionPort git) {
        this.git = Objects.requireNonNull(git, "git must not be null");
    }

    public Optional<LocatedRepository> locate(ResolvedAuthorizedPath target) {
        Objects.requireNonNull(target, "target must not be null");
        Path start =
                nearestExistingDirectory(target.hostPath(), target.directory().realPath());
        CacheKey key = new CacheKey(target.directory().workspaceId(), start);
        return cache.computeIfAbsent(key, ignored -> locateUncached(target.directory(), start));
    }

    public void invalidate(WorkspaceId workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        cache.keySet().removeIf(key -> key.workspaceId().equals(workspaceId));
    }

    private Optional<LocatedRepository> locateUncached(AuthorizedHostDirectory boundary, Path start) {
        Path current = start;
        while (current != null && current.startsWith(boundary.realPath())) {
            final HostGitInspectionStatus status;
            try {
                status = git.inspect(boundary, current);
            } catch (RuntimeException exception) {
                throw new HostGitInspectionUnavailableException(exception);
            }
            if (status == HostGitInspectionStatus.UNAVAILABLE) {
                throw new HostGitInspectionUnavailableException();
            }
            if (status == HostGitInspectionStatus.WORKTREE_ROOT) {
                String relative =
                        boundary.realPath().relativize(current).toString().replace('\\', '/');
                WorkspacePath logicalRoot = new WorkspacePath(boundary.workspaceId(), ProjectPath.of(relative));
                return Optional.of(new LocatedRepository(boundary, logicalRoot, current));
            }
            if (current.equals(boundary.realPath())) break;
            current = current.getParent();
        }
        return Optional.empty();
    }

    private static Path nearestExistingDirectory(Path target, Path boundary) {
        Path current = Files.isDirectory(target) ? target : target.getParent();
        while (current != null && current.startsWith(boundary) && !Files.isDirectory(current)) {
            current = current.getParent();
        }
        return current == null || !current.startsWith(boundary) ? boundary : current;
    }

    private record CacheKey(WorkspaceId workspaceId, Path start) {
        private CacheKey {
            workspaceId = Objects.requireNonNull(workspaceId, "workspaceId must not be null");
            start = Objects.requireNonNull(start, "start must not be null")
                    .toAbsolutePath()
                    .normalize();
        }
    }
}
