package io.haifa.agent.starter;

import io.haifa.agent.common.io.SecureFilePermissions;
import io.haifa.agent.execution.core.tool.ExecutionToolProvider;
import io.haifa.agent.execution.core.tool.TrustedSkillScriptMaterialization;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Materializes the verified frozen Skill package bytes of one invocation into a per-invocation
 * directory below the Workspace root, then deletes it. It performs physical file placement only:
 * digest verification lives in the Execution module, so no second control model exists.
 */
final class SkillScriptPackageMaterializer implements TrustedSkillScriptMaterialization {
    static final String MATERIALIZATION_DIRECTORY = ".haifa-trusted-skills";

    private final SkillScriptPlatformWorkspaceAccess access;

    SkillScriptPackageMaterializer(SkillScriptPlatformWorkspaceAccess access) {
        this.access = Objects.requireNonNull(access, "access must not be null");
    }

    @Override
    public StagedSkillPackage stage(String materializationKey, List<PackageFile> files, String entryRelativePath) {
        TrustedSkillScriptMaterialization.requireTrustedKey(materializationKey);
        String normalizedEntryPath =
                ExecutionToolProvider.requireRelativeSegmentPath(entryRelativePath, "entryRelativePath");
        Path workspaceRoot = access.workspaceRoot();
        Path stagingRoot = workspaceRoot.resolve(MATERIALIZATION_DIRECTORY);
        Path stagingDirectory = stagingRoot.resolve(materializationKey);
        if (Files.isSymbolicLink(workspaceRoot) || Files.isSymbolicLink(stagingRoot)) {
            throw new IOExceptionUnchecked("trusted script staging must not follow links");
        }
        if (Files.exists(stagingDirectory)) {
            throw new IllegalArgumentException("trusted script materialization key is already in use");
        }
        try {
            createSecuredDirectory(stagingRoot, workspaceRoot);
            createSecuredDirectory(stagingDirectory, workspaceRoot);
            for (PackageFile file : files) {
                Path target = stagingDirectory.resolve(file.relativePath());
                createSecuredDirectory(target.getParent(), stagingDirectory);
                Files.write(target, file.content(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                SecureFilePermissions.secureFile(target);
            }
        } catch (IOException exception) {
            removeRecursively(stagingDirectory);
            throw new UncheckedIOException("trusted script package could not be materialized", exception);
        }
        return new StagedSkillPackage(
                materializationKey,
                ExecutionToolProvider.requireRelativeSegmentPath(
                        MATERIALIZATION_DIRECTORY + "/" + materializationKey + "/" + normalizedEntryPath,
                        "workspaceRelativeEntryPath"),
                files.stream().map(PackageFile::relativePath).toList());
    }

    @Override
    public void release(StagedSkillPackage staged) {
        Objects.requireNonNull(staged, "staged must not be null");
        Path stagingRoot = access.workspaceRoot().resolve(MATERIALIZATION_DIRECTORY);
        removeRecursively(stagingRoot.resolve(staged.materializationKey()));
        try {
            Files.deleteIfExists(stagingRoot);
        } catch (IOException | RuntimeException ignored) {
            // Best-effort; another invocation may legitimately be staging below the same root.
        }
    }

    private static void createSecuredDirectory(Path directory, Path boundedRoot) throws IOException {
        if (!directory.startsWith(boundedRoot)
                || (Files.exists(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        && Files.isSymbolicLink(directory))) {
            throw new IOException("trusted script staging escaped its bounded root");
        }
        Files.createDirectories(directory);
        SecureFilePermissions.secureDirectory(directory);
    }

    /** Best-effort recursive delete bounded by the invocation staging directory. */
    private static void removeRecursively(Path directory) {
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Release is best-effort and never changes the authoritative Tool result.
                }
            });
        } catch (RuntimeException | IOException ignored) {
            // Release is best-effort and never changes the authoritative Tool result.
        }
    }

    private static final class IOExceptionUnchecked extends RuntimeException {
        private IOExceptionUnchecked(String message) {
            super(message);
        }
    }
}
