package io.haifa.agent.project.hostworkspace.scope;

import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable snapshot of the peer authorized directories of one host session. Roots must be strictly
 * disjoint so a target can never match two permissions; overlap between a parent and a child
 * directory is rejected at construction time instead of being resolved by prefix order. Resolution
 * accepts host absolute paths only, verifies physical containment via real paths (symlink and
 * reparse-point defense, including the nearest existing ancestor for targets that do not exist yet)
 * and maps the verified target to a logical {@link WorkspacePath}.
 *
 * <p>The {@code version} increments whenever the directory set changes. Write paths capture the
 * snapshot they were resolved against and must re-validate the version before physical I/O so a
 * concurrent revocation fails closed.
 */
public record HostWorkspaceScope(List<AuthorizedHostDirectory> allowedDirectories, long version) {

    public HostWorkspaceScope {
        allowedDirectories =
                List.copyOf(Objects.requireNonNull(allowedDirectories, "allowedDirectories must not be null"));
        if (allowedDirectories.isEmpty()) {
            throw new IllegalArgumentException("allowedDirectories must not be empty");
        }
        validateDisjointRoots(allowedDirectories);
    }

    public static HostWorkspaceScope initial(AuthorizedHostDirectory directory) {
        Objects.requireNonNull(directory, "directory must not be null");
        return new HostWorkspaceScope(List.of(directory), 1L);
    }

    public HostWorkspaceScope withDirectory(AuthorizedHostDirectory directory) {
        Objects.requireNonNull(directory, "directory must not be null");
        List<AuthorizedHostDirectory> next = new ArrayList<>(allowedDirectories.size() + 1);
        next.addAll(allowedDirectories);
        next.add(directory);
        return new HostWorkspaceScope(next, version + 1);
    }

    public HostWorkspaceScope withoutDirectory(WorkspaceId workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        List<AuthorizedHostDirectory> remaining = allowedDirectories.stream()
                .filter(directory -> !directory.workspaceId().equals(workspaceId))
                .toList();
        if (remaining.size() == allowedDirectories.size()) {
            return this;
        }
        return new HostWorkspaceScope(remaining, version + 1);
    }

    public AuthorizedHostDirectory findEnclosingDirectory(Path candidateRealPath) {
        for (AuthorizedHostDirectory directory : allowedDirectories) {
            if (directory.encloses(candidateRealPath)) {
                return directory;
            }
        }
        return null;
    }

    /**
     * Resolves a raw tool input that must be a host absolute path. Relative paths, root aliases and
     * blank inputs are rejected with {@code INVALID_ARGUMENT}; targets outside every authorized
     * directory are rejected with {@code ACCESS_DENIED}; targets that physically resolve outside the
     * enclosing boundary via links are rejected with {@code PATH_ESCAPE_DENIED}.
     */
    public ResolvedAuthorizedPath resolve(String input) {
        if (input == null || input.isBlank()) {
            throw HostWorkspaceScopeException.invalidArgument(
                    input, "Local file tools accept absolute host paths only");
        }
        String trimmed = input.trim();
        Path candidate;
        try {
            candidate = Path.of(trimmed);
        } catch (InvalidPathException exception) {
            throw HostWorkspaceScopeException.invalidArgument(trimmed, "Input is not a valid host path: " + trimmed);
        }
        if (!candidate.isAbsolute()) {
            throw HostWorkspaceScopeException.invalidArgument(
                    trimmed,
                    "Relative paths and root aliases such as 'main:' or 'docs:' are not accepted;"
                            + " pass a host absolute path: "
                            + trimmed);
        }
        Path normalized = candidate.normalize();
        AuthorizedHostDirectory directory = findEnclosingDirectory(normalized);
        if (directory == null) {
            throw HostWorkspaceScopeException.accessDenied(
                    trimmed, "Path is outside every authorized directory: " + normalized);
        }
        Path verified = verifyPhysicalContainment(normalized, directory);
        return new ResolvedAuthorizedPath(trimmed, directory, toWorkspacePath(directory, verified), verified);
    }

    /** Fails closed when the resolved directory does not allow writes. */
    public void requireWritable(AuthorizedHostDirectory directory) {
        Objects.requireNonNull(directory, "directory must not be null");
        if (!directory.permission().canWrite()) {
            throw HostWorkspaceScopeException.permissionDenied(
                    directory.realPath().toString(), "Authorized directory is read-only: " + directory.realPath());
        }
    }

    private Path verifyPhysicalContainment(Path normalized, AuthorizedHostDirectory directory) {
        Path realRoot = directory.realPath();
        try {
            if (Files.exists(normalized)) {
                Path realTarget = normalized.toRealPath();
                if (!realTarget.startsWith(realRoot)) {
                    throw HostWorkspaceScopeException.pathEscapeDenied(
                            normalized.toString(),
                            "Target escapes the authorized directory via symlink or reparse point: " + normalized);
                }
                return realTarget;
            }
            Path current = normalized.getParent();
            while (current != null && !Files.exists(current)) {
                current = current.getParent();
            }
            if (current == null) {
                throw HostWorkspaceScopeException.pathEscapeDenied(
                        normalized.toString(),
                        "Target cannot be anchored inside the authorized directory: " + normalized);
            }
            Path realAncestor = current.toRealPath();
            if (!realAncestor.startsWith(realRoot)) {
                throw HostWorkspaceScopeException.pathEscapeDenied(
                        normalized.toString(),
                        "Ancestor directory escapes the authorized directory via symlink or reparse point: "
                                + normalized);
            }
            return normalized;
        } catch (IOException exception) {
            throw HostWorkspaceScopeException.pathEscapeDenied(
                    normalized.toString(), "Cannot verify physical containment of the target: " + normalized);
        }
    }

    private WorkspacePath toWorkspacePath(AuthorizedHostDirectory directory, Path verifiedHostPath) {
        String logical =
                directory.realPath().relativize(verifiedHostPath).toString().replace('\\', '/');
        try {
            return new WorkspacePath(directory.workspaceId(), ProjectPath.of(logical));
        } catch (IllegalArgumentException exception) {
            throw HostWorkspaceScopeException.invalidArgument(
                    verifiedHostPath.toString(),
                    "Path cannot be represented as a logical workspace path: " + exception.getMessage());
        }
    }

    private static void validateDisjointRoots(List<AuthorizedHostDirectory> directories) {
        for (int i = 0; i < directories.size(); i++) {
            Path first = directories.get(i).realPath();
            for (int j = i + 1; j < directories.size(); j++) {
                Path second = directories.get(j).realPath();
                if (first.startsWith(second) || second.startsWith(first)) {
                    throw new IllegalArgumentException(
                            "Overlapping authorized directories are forbidden: " + first + " overlaps " + second);
                }
            }
        }
    }
}
