package io.haifa.agent.starter;

import io.haifa.agent.common.io.SecureFilePermissions;
import io.haifa.agent.execution.core.tool.ExecutionToolProvider;
import io.haifa.agent.execution.core.tool.TrustedSkillScriptPackageDirectory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Prepares and reuses the immutable execution directory of one frozen Skill package version below
 * the Workspace root. The verification of every frozen byte is owned by the Execution module, and
 * the file supplier is only evaluated when the version directory still has to be created, so a
 * package version is never copied per invocation and the prepared directory is never deleted to
 * simulate isolation. Prepared directories persist for reuse until the version is no longer used.
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
        Object lock = locks.computeIfAbsent(versionKey, ignored -> new Object());
        synchronized (lock) {
            if (!isPrepared(directory, normalizedEntryPath)) {
                prepare(directory, files);
            }
        }
        return prepared(versionKey, normalizedEntryPath);
    }

    /** Verifies the frozen bytes first, then places them once; nothing is placed on drift. */
    private void prepare(Path directory, Supplier<List<PackageFile>> files) {
        List<PackageFile> packageFiles = files.get();
        try {
            if (Files.exists(directory)) {
                removeRecursively(directory);
            }
            Path workspaceRoot = access.workspaceRoot();
            createSecuredDirectory(directory.getParent(), workspaceRoot);
            createSecuredDirectory(directory, workspaceRoot);
            for (PackageFile file : packageFiles) {
                Path target = directory.resolve(file.relativePath());
                createSecuredDirectory(target.getParent(), directory);
                Files.write(target, file.content(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                SecureFilePermissions.secureFile(target);
            }
            Files.writeString(directory.resolve(PREPARED_MARKER), "prepared", StandardOpenOption.CREATE_NEW);
            SecureFilePermissions.secureFile(directory.resolve(PREPARED_MARKER));
        } catch (IOException exception) {
            removeRecursively(directory);
            throw new UncheckedIOException("trusted Skill package directory could not be prepared", exception);
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

    /** Best-effort recursive delete bounded by the version directory. */
    private static void removeRecursively(Path directory) {
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Replacement of a never-completed directory is best-effort and bounded.
                }
            });
        } catch (RuntimeException | IOException ignored) {
            // Replacement of a never-completed directory is best-effort and bounded.
        }
    }

    private static final class IOExceptionUnchecked extends RuntimeException {
        private IOExceptionUnchecked(String message) {
            super(message);
        }
    }
}
