package io.haifa.agent.project.provider.local.root;

import io.haifa.agent.project.root.MultiRootPath;
import io.haifa.agent.project.root.WorkspaceRootAlias;
import io.haifa.agent.project.root.WorkspaceRootErrorCode;
import io.haifa.agent.project.root.WorkspaceRootException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Resolves multi-root path strings into host filesystem Paths with comprehensive anti-escape checks.
 */
public final class LocalMultiRootPathResolver {
    private static final Pattern WINDOWS_DRIVE_PATTERN = Pattern.compile("^[a-zA-Z]:[\\/]?.*");

    private LocalMultiRootPathResolver() {}

    /**
     * Parses a raw input path into a MultiRootPath.
     * Supports formats like 'main:src/App.java', 'docs:guide.md', or 'src/App.java' (defaults to main).
     * Rejects host absolute paths (e.g. /etc/passwd, D:/foo).
     */
    public static MultiRootPath parse(String input) {
        if (input == null || input.isBlank()) {
            return MultiRootPath.ofMain("");
        }
        String trimmed = input.trim();

        // 1. Defend against Windows drive letter paths like D:/foo or C:\bar
        if (WINDOWS_DRIVE_PATTERN.matcher(trimmed).matches()) {
            throw new WorkspaceRootException(
                    WorkspaceRootErrorCode.ABSOLUTE_PATH_FORBIDDEN,
                    null,
                    trimmed,
                    "Absolute host path is forbidden; use 'rootAlias:relativePath' syntax instead: " + trimmed);
        }

        // 2. Defend against POSIX absolute paths like /etc/passwd
        if (trimmed.startsWith("/") || trimmed.startsWith("\\")) {
            throw new WorkspaceRootException(
                    WorkspaceRootErrorCode.ABSOLUTE_PATH_FORBIDDEN,
                    null,
                    trimmed,
                    "Absolute host path is forbidden; use 'rootAlias:relativePath' syntax instead: " + trimmed);
        }

        // 3. Check for alias delimiter ':'
        int colonIndex = trimmed.indexOf(':');
        if (colonIndex < 0) {
            // No alias specified -> default to 'main'
            return MultiRootPath.ofMain(trimmed);
        }

        String aliasPart = trimmed.substring(0, colonIndex).trim();
        String pathPart = trimmed.substring(colonIndex + 1).trim();

        if (aliasPart.isEmpty()) {
            throw new WorkspaceRootException(
                    WorkspaceRootErrorCode.INVALID_ROOT_ALIAS,
                    aliasPart,
                    trimmed,
                    "Empty root alias is invalid in path: " + trimmed);
        }

        WorkspaceRootAlias alias;
        try {
            alias = WorkspaceRootAlias.of(aliasPart);
        } catch (IllegalArgumentException e) {
            throw new WorkspaceRootException(
                    WorkspaceRootErrorCode.INVALID_ROOT_ALIAS,
                    aliasPart,
                    trimmed,
                    "Invalid root alias '" + aliasPart + "': " + e.getMessage());
        }

        return MultiRootPath.of(alias, pathPart);
    }

    /**
     * Resolves a MultiRootPath or raw input string against the provided LocalWorkspaceRootRegistry.
     * Enforces path containment and symlink/reparse-point escape prevention.
     */
    public static ResolvedRootPath resolve(LocalWorkspaceRootRegistry registry, String input) {
        Objects.requireNonNull(registry, "registry must not be null");
        MultiRootPath multiRootPath = parse(input);
        return resolve(registry, multiRootPath);
    }

    public static ResolvedRootPath resolve(LocalWorkspaceRootRegistry registry, MultiRootPath path) {
        Objects.requireNonNull(registry, "registry must not be null");
        Objects.requireNonNull(path, "path must not be null");

        LocalWorkspaceRoot root = registry.require(path.rootAlias());
        Path hostRoot = root.hostPath().toAbsolutePath().normalize();

        Path resolved =
                path.isRoot() ? hostRoot : hostRoot.resolve(path.relativePath()).normalize();

        // Check logical containment
        if (!resolved.startsWith(hostRoot)) {
            throw new WorkspaceRootException(
                    WorkspaceRootErrorCode.PATH_ESCAPE_FORBIDDEN,
                    path.rootAlias().value(),
                    path.relativePath(),
                    "Path escapes root directory boundary: " + path);
        }

        // Check physical real path if existing (symlink / reparse-point defense)
        if (Files.exists(resolved)) {
            try {
                Path realResolved = resolved.toRealPath();
                Path realRoot = hostRoot.toRealPath();
                if (!realResolved.startsWith(realRoot)) {
                    throw new WorkspaceRootException(
                            WorkspaceRootErrorCode.PATH_ESCAPE_FORBIDDEN,
                            path.rootAlias().value(),
                            path.relativePath(),
                            "Physical path escapes root directory via symlink/reparse-point: " + path);
                }
            } catch (IOException e) {
                // If real path fails due to IO, we fallback to resolved normalize check
            }
        }

        return new ResolvedRootPath(root, path, resolved);
    }

    public record ResolvedRootPath(LocalWorkspaceRoot root, MultiRootPath multiRootPath, Path hostPath) {}
}
