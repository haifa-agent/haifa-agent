package io.haifa.agent.execution.core.tool;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Trusted seam that places the frozen Skill package files of one invocation into a host directory
 * below the Workspace root, and releases it afterwards. The Execution module owns the digest
 * verification; the implementation owns only physical file placement and cleanup, so no second
 * execution engine exists at this boundary.
 */
public interface TrustedSkillScriptMaterialization {

    /** One frozen package file with its package-relative path and exact frozen bytes. */
    record PackageFile(String relativePath, byte[] content) {
        private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

        public PackageFile {
            relativePath = Objects.requireNonNull(relativePath, "relativePath must not be null")
                    .trim()
                    .replace('\\', '/');
            if (relativePath.startsWith("/") || relativePath.length() > 512) {
                throw new IllegalArgumentException("package file path must be package-relative");
            }
            for (String segment : relativePath.split("/")) {
                if (!SEGMENT.matcher(segment).matches()) {
                    throw new IllegalArgumentException("package file path has an invalid segment");
                }
            }
            content =
                    Objects.requireNonNull(content, "content must not be null").clone();
        }
    }

    /** The materialized result handed to {@link ExecutionToolProvider} for entry-file execution. */
    record StagedSkillPackage(
            String materializationKey, String workspaceRelativeEntryPath, List<String> relativeFilePaths) {
        public StagedSkillPackage {
            requireTrustedKey(materializationKey);
            workspaceRelativeEntryPath = ExecutionToolProvider.requireRelativeSegmentPath(
                    workspaceRelativeEntryPath, "workspaceRelativeEntryPath");
            relativeFilePaths =
                    List.copyOf(Objects.requireNonNull(relativeFilePaths, "relativeFilePaths must not be null"));
            if (relativeFilePaths.isEmpty()) {
                throw new IllegalArgumentException("staged package contains no files");
            }
        }
    }

    /**
     * Places the verified frozen package bytes into a per-invocation directory below the Workspace
     * root. Implementations reject a key that is not a lowercase hex digest and any path escape.
     */
    StagedSkillPackage stage(String materializationKey, List<PackageFile> files, String entryRelativePath);

    /** Deletes the materialized directory. Implementations are idempotent and never throw on missing files. */
    void release(StagedSkillPackage staged);

    /** Fails closed for providers that cannot stage entry files; no provider is ever constructed with this. */
    static TrustedSkillScriptMaterialization ofNothingStaged() {
        return new TrustedSkillScriptMaterialization() {
            @Override
            public StagedSkillPackage stage(String key, List<PackageFile> files, String entryRelativePath) {
                throw new UnsupportedOperationException("trusted script materialization is not configured");
            }

            @Override
            public void release(StagedSkillPackage staged) {}
        };
    }

    /** Shared fail-closed validation of the per-invocation materialization key. */
    static void requireTrustedKey(String key) {
        Objects.requireNonNull(key, "materializationKey must not be null");
        if (!key.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("materializationKey is invalid");
        }
    }
}
