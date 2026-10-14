package io.haifa.agent.execution.core.tool;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Trusted seam that prepares the reusable, immutable execution directory of one frozen Skill
 * package version below the Workspace root. {@link #prepareIfAbsent} loads and verifies the frozen
 * package bytes only when the version directory is still absent and reuses the prepared directory
 * on every later call of the same version, so no invocation copies or deletes the whole package.
 * The Execution module owns the digest verification; the implementation owns only physical file
 * placement, so no second execution engine exists at this boundary.
 */
public interface TrustedSkillScriptPackageDirectory {

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

    /** The prepared immutable version directory handed to {@link ExecutionToolProvider}. */
    record PreparedSkillPackage(String versionKey, String workspaceRelativeEntryPath) {
        public PreparedSkillPackage {
            requireVersionKey(versionKey);
            workspaceRelativeEntryPath = ExecutionToolProvider.requireRelativeSegmentPath(
                    workspaceRelativeEntryPath, "workspaceRelativeEntryPath");
        }
    }

    /**
     * Reuses the already prepared immutable directory of one Skill package version, or prepares it
     * exactly once from verified frozen bytes. The file supplier is invoked only when the version
     * directory is still missing, so repeated invocations never re-read the package bytes, and a
     * directory that exists but was never completed is replaced atomically for this key.
     */
    PreparedSkillPackage prepareIfAbsent(
            String versionKey, Supplier<List<PackageFile>> files, String entryRelativePath);

    /** Fails closed for providers that execute through standard input only. */
    static TrustedSkillScriptPackageDirectory unsupported() {
        return (versionKey, files, entryRelativePath) -> {
            throw new UnsupportedOperationException(
                    "trusted Skill package directories are not configured for standard-input script Tools");
        };
    }

    /** Shared fail-closed validation of one 64-hex version key. */
    static void requireVersionKey(String key) {
        Objects.requireNonNull(key, "versionKey must not be null");
        if (!key.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("versionKey is invalid");
        }
    }
}
