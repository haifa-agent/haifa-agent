package io.haifa.agent.starter;

import io.haifa.agent.common.io.SecureFilePermissions;
import io.haifa.agent.execution.core.tool.ExecutionToolProvider;
import io.haifa.agent.execution.core.tool.TrustedSkillScriptPackageDirectory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Prepares and reuses the immutable execution directory of one frozen Skill package version below
 * the Workspace root. The verification of every frozen byte is owned by the Execution module, and
 * the file supplier is only evaluated when the version directory still has to be created, so a
 * package version is never copied per invocation and the prepared directory is never deleted to
 * simulate isolation. Prepared directories persist for reuse until the version is no longer used.
 *
 * <p>Preparation is safe across independent platforms that declare the same workspace: a fully
 * verified package is written into a process-unique temporary directory and only then published by
 * one atomic rename into the version path. A completed directory is therefore never removed,
 * truncated or rebuilt - concurrent preparers either reuse the published directory or lose the
 * rename race and discard only their own temporary directory. An interrupted preparation leaves
 * no final directory behind, so an incomplete state can never be mistaken for a prepared version.
 */
final class SkillScriptPackageDirectory implements TrustedSkillScriptPackageDirectory {
    static final String DIRECTORY = ".haifa-trusted-skills";
    static final String PREPARED_MARKER = ".haifa-prepared-v1";

    private final SkillScriptPlatformWorkspaceAccess access;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    SkillScriptPackageDirectory(SkillScriptPlatformWorkspaceAccess access) {
        this.access = Objects.requireNonNull(access, "access must not be null");
    }

    @Override
    public PreparedSkillPackage prepareIfAbsent(
            String versionKey, Supplier<List<PackageFile>> files, String entryRelativePath) {
        TrustedSkillScriptPackageDirectory.requireVersionKey(versionKey);
        String normalizedEntryPath =
                ExecutionToolProvider.requireRelativeSegmentPath(entryRelativePath, "entryRelativePath");
        Path workspaceRoot = access.workspaceRoot();
        Path preparedRoot = workspaceRoot.resolve(DIRECTORY);
        if (Files.isSymbolicLink(workspaceRoot) || Files.isSymbolicLink(preparedRoot)) {
            throw new IOExceptionUnchecked("trusted Skill package directories must not follow links");
        }
        Path directory = preparedRoot.resolve(versionKey);
        if (Files.isSymbolicLink(directory)) {
            throw new IOExceptionUnchecked("trusted Skill package directories must not follow links");
        }
        Object lock = locks.computeIfAbsent(versionKey, ignored -> new Object());
        synchronized (lock) {
            if (!isPrepared(directory, normalizedEntryPath)) {
                publishAtomically(preparedRoot, directory, normalizedEntryPath, files);
            }
        }
        if (!isPrepared(directory, normalizedEntryPath)) {
            throw new IOExceptionUnchecked(
                    "trusted Skill package version directory exists but is not prepared; refusing to"
                            + " reuse or rebuild a directory of unknown state");
        }
        return prepared(versionKey, normalizedEntryPath);
    }

    /**
     * Verifies the frozen bytes first, writes the whole package into a process-unique temporary
     * directory below the shared prepared root, and publishes it with one atomic rename. Nothing
     * is placed on drift and a completed directory is never removed or replaced.
     */
    private void publishAtomically(
            Path preparedRoot, Path directory, String normalizedEntryPath, Supplier<List<PackageFile>> files) {
        List<PackageFile> packageFiles = files.get();
        Path temporary = directory.getParent().resolve("." + directory.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            createSecuredDirectory(preparedRoot, access.workspaceRoot());
            createSecuredDirectory(temporary, access.workspaceRoot());
            for (PackageFile file : packageFiles) {
                Path target = temporary.resolve(file.relativePath());
                createSecuredDirectory(target.getParent(), temporary);
                Files.write(target, file.content(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                SecureFilePermissions.secureFile(target);
            }
            Files.writeString(temporary.resolve(PREPARED_MARKER), "prepared", StandardOpenOption.CREATE_NEW);
            SecureFilePermissions.secureFile(temporary.resolve(PREPARED_MARKER));
            publish(temporary, directory, normalizedEntryPath);
        } catch (IOException exception) {
            removeRecursively(temporary);
            throw new UncheckedIOException("trusted Skill package directory could not be prepared", exception);
        }
    }

    /**
     * Publishes the fully verified temporary directory with one rename. If another platform
     * instance won the race and published the version first, the move fails (platform-specific
     * {@code AccessDenied} / {@code FileAlreadyExists}); the completed directory is then kept as
     * published and only the losing temporary directory is discarded. A failing move against a
     * target that is not a completed version directory is rethrown.
     */
    private static void publish(Path temporary, Path directory, String normalizedEntryPath) throws IOException {
        try {
            try {
                Files.move(temporary, directory, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, directory);
            }
        } catch (IOException race) {
            if (isPrepared(directory, normalizedEntryPath)) {
                removeRecursively(temporary);
                return;
            }
            throw race;
        }
    }

    private static PreparedSkillPackage prepared(String versionKey, String normalizedEntryPath) {
        return new PreparedSkillPackage(
                versionKey,
                ExecutionToolProvider.requireRelativeSegmentPath(
                        DIRECTORY + "/" + versionKey + "/" + normalizedEntryPath, "workspaceRelativeEntryPath"));
    }

    private static boolean isPrepared(Path directory, String normalizedEntryPath) {
        return Files.isRegularFile(directory.resolve(PREPARED_MARKER), LinkOption.NOFOLLOW_LINKS)
                && Files.isRegularFile(directory.resolve(normalizedEntryPath), LinkOption.NOFOLLOW_LINKS);
    }

    private static void createSecuredDirectory(Path directory, Path boundedRoot) throws IOException {
        if (!directory.startsWith(boundedRoot)
                || (Files.exists(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        && Files.isSymbolicLink(directory))) {
            throw new IOException("trusted Skill package directory escaped its bounded root");
        }
        Files.createDirectories(directory);
        SecureFilePermissions.secureDirectory(directory);
    }

    /** Best-effort recursive delete bounded to the process-own temporary directory. */
    private static void removeRecursively(Path directory) {
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Discarding our own losing temporary directory is best-effort and bounded.
                }
            });
        } catch (RuntimeException | IOException ignored) {
            // Discarding our own losing temporary directory is best-effort and bounded.
        }
    }

    private static final class IOExceptionUnchecked extends RuntimeException {
        private IOExceptionUnchecked(String message) {
            super(message);
        }
    }
}
