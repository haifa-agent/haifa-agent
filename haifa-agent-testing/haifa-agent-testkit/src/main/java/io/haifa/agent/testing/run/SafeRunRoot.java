package io.haifa.agent.testing.run;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Objects;

/** Resolves repository-external run locations without trusting unresolved symbolic-link ancestors. */
public final class SafeRunRoot {
    private SafeRunRoot() {}

    public static Path requireExternalLocation(Path value, Collection<Path> repositoryRoots, String label)
            throws IOException {
        String field = requireLabel(label);
        Path candidate = Objects.requireNonNull(value, field + " must not be null")
                .toAbsolutePath()
                .normalize();
        Path resolved = resolveThroughExistingAncestor(candidate);
        if (Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(resolved, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(field + " must be a directory or a new directory location");
        }
        rejectBroadOrRepositoryLocation(resolved, repositoryRoots, field);
        return resolved;
    }

    public static Path requireExternalExistingParent(Path value, Collection<Path> repositoryRoots, String label)
            throws IOException {
        String field = requireLabel(label);
        Path resolved = requireExternalLocation(value, repositoryRoots, field);
        if (!Files.isDirectory(resolved, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(field + " must be an existing directory");
        }
        return resolved.toRealPath();
    }

    private static Path resolveThroughExistingAncestor(Path candidate) throws IOException {
        Path existing = candidate;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new IllegalArgumentException("run location has no existing filesystem ancestor");
        }
        Path realAncestor = existing.toRealPath();
        return realAncestor.resolve(existing.relativize(candidate)).normalize();
    }

    private static void rejectBroadOrRepositoryLocation(Path candidate, Collection<Path> repositoryRoots, String label)
            throws IOException {
        Objects.requireNonNull(repositoryRoots, "repositoryRoots must not be null");
        Path home = Path.of(System.getProperty("user.home"))
                .toAbsolutePath()
                .normalize()
                .toRealPath();
        if (candidate.getParent() == null || candidate.equals(home)) {
            throw new IllegalArgumentException(label + " must not be a filesystem root or user home");
        }
        for (Path repositoryRoot : repositoryRoots) {
            Path repository = Objects.requireNonNull(repositoryRoot, "repository root must not be null")
                    .toAbsolutePath()
                    .normalize()
                    .toRealPath();
            if (candidate.startsWith(repository) || repository.startsWith(candidate)) {
                throw new IllegalArgumentException(label + " must not overlap a Git repository");
            }
        }
    }

    private static String requireLabel(String label) {
        String normalized =
                Objects.requireNonNull(label, "label must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("label must not be blank");
        return normalized;
    }
}
