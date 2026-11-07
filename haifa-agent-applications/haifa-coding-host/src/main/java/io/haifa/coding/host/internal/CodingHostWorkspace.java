package io.haifa.coding.host.internal;

import java.nio.file.Path;
import java.util.Map;

/** Display-only facts from the Host's authorized initial workspace, never from a client's current directory. */
public final class CodingHostWorkspace {
    private CodingHostWorkspace() {}

    public static Map<String, String> summary(Path root) {
        var git = LocalGitBranchResolver.resolveDetails(root);
        return Map.of(
                "workingDirectory",
                safe(git.projectPath()),
                "gitBranch",
                safe(git.gitBranch()),
                "worktree",
                safe(git.worktreePath()),
                "worktreeBranch",
                safe(git.worktreeBranch()),
                "executionDirectory",
                safe(root.toAbsolutePath().normalize().toString()));
    }

    private static String safe(String value) {
        String clean = value.codePoints()
                .filter(point -> !Character.isISOControl(point))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
        if (clean.length() <= 4096) return clean;
        int end = Character.isHighSurrogate(clean.charAt(4094)) ? 4094 : 4095;
        return clean.substring(0, end) + "…";
    }
}
