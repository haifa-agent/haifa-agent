package io.haifa.agent.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;

/** Best-effort, process-free Git branch discovery for terminal presentation only. */
final class LocalGitBranchResolver {
    private static final int MAX_METADATA_CHARS = 8_192;

    private LocalGitBranchResolver() {}

    record GitWorkspaceDetails(
            String projectPath,
            String gitBranch,
            String worktreePath,
            String worktreeBranch) {
        GitWorkspaceDetails {
            projectPath = projectPath == null ? "" : projectPath.strip();
            gitBranch = gitBranch == null ? "" : gitBranch.strip();
            worktreePath = worktreePath == null ? "" : worktreePath.strip();
            worktreeBranch = worktreeBranch == null ? "" : worktreeBranch.strip();
        }

        static GitWorkspaceDetails standard(String projectPath, String gitBranch) {
            return new GitWorkspaceDetails(projectPath, gitBranch, "", "");
        }
    }

    static GitWorkspaceDetails resolveDetails(Path workspace) {
        Path absWorkspace = workspace.toAbsolutePath().normalize();
        Path metadata = absWorkspace.resolve(".git");
        try {
            if (Files.isDirectory(metadata, LinkOption.NOFOLLOW_LINKS)) {
                String branch = readBranch(metadata).orElse("");
                return GitWorkspaceDetails.standard(absWorkspace.toString(), branch);
            }
            Path linkedDirectory = linkedGitDirectory(metadata);
            if (linkedDirectory == null || !Files.isDirectory(linkedDirectory, LinkOption.NOFOLLOW_LINKS)) {
                return GitWorkspaceDetails.standard(absWorkspace.toString(), "");
            }
            Path commondirFile = linkedDirectory.resolve("commondir");
            if (Files.isRegularFile(commondirFile, LinkOption.NOFOLLOW_LINKS)) {
                String commondirStr = boundedRead(commondirFile).strip();
                if (!commondirStr.isEmpty()) {
                    Path commonTarget = Path.of(commondirStr);
                    Path commonGitDir = (commonTarget.isAbsolute()
                                    ? commonTarget
                                    : linkedDirectory.resolve(commonTarget))
                            .normalize();
                    if (Files.isDirectory(commonGitDir, LinkOption.NOFOLLOW_LINKS)) {
                        Path mainProject = commonGitDir.getParent();
                        String mainPath = mainProject != null
                                ? mainProject.toAbsolutePath().normalize().toString()
                                : absWorkspace.toString();
                        String mainBranch = readBranch(commonGitDir).orElse("");
                        String worktreeBranch = readBranch(linkedDirectory).orElse("");
                        return new GitWorkspaceDetails(mainPath, mainBranch, absWorkspace.toString(), worktreeBranch);
                    }
                }
            }
            String branch = readBranch(linkedDirectory).orElse("");
            return GitWorkspaceDetails.standard(absWorkspace.toString(), branch);
        } catch (IOException | IllegalArgumentException ignored) {
            return GitWorkspaceDetails.standard(absWorkspace.toString(), "");
        }
    }

    static Optional<String> resolve(Path workspace) {
        GitWorkspaceDetails details = resolveDetails(workspace);
        String branch = !details.worktreeBranch().isBlank() ? details.worktreeBranch() : details.gitBranch();
        return branch.isBlank() ? Optional.empty() : Optional.of(branch);
    }

    private static Optional<String> readBranch(Path gitDirectory) {
        if (gitDirectory == null || !Files.isDirectory(gitDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            String head = boundedRead(gitDirectory.resolve("HEAD")).strip();
            String prefix = "ref: refs/heads/";
            if (!head.startsWith(prefix)) return Optional.empty();
            String branch = head.substring(prefix.length()).strip();
            return branch.isEmpty()
                            || branch.length() > 512
                            || branch.codePoints().anyMatch(Character::isISOControl)
                    ? Optional.empty()
                    : Optional.of(branch);
        } catch (IOException | IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static Path linkedGitDirectory(Path metadata) throws IOException {
        if (!Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(metadata)) return null;
        String pointer = boundedRead(metadata).strip();
        String prefix = "gitdir:";
        if (!pointer.regionMatches(true, 0, prefix, 0, prefix.length())) return null;
        String value = pointer.substring(prefix.length()).strip();
        if (value.isEmpty()) return null;
        Path target = Path.of(value);
        return (target.isAbsolute() ? target : metadata.getParent().resolve(target)).normalize();
    }

    private static String boundedRead(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(path)
                || Files.size(path) > MAX_METADATA_CHARS) {
            throw new IOException("Git metadata is unavailable");
        }
        return Files.readString(path);
    }
}
