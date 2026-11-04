package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalGitBranchResolverTest {
    @TempDir
    Path directory;

    @Test
    void nonGitHostRootHasAnExplicitDisplayFallbackWithoutClientFilesystemAccess() {
        var wire = CodingHostWorkspace.summary(directory);
        assertThat(wire)
                .containsEntry(
                        "workingDirectory",
                        directory.toAbsolutePath().normalize().toString())
                .containsEntry("gitBranch", "")
                .containsEntry("worktree", "");
        var metadata = new io.haifa.agent.cli.remote.RemoteCodingWorkspaceInfo(
                wire.get("workingDirectory"),
                wire.get("gitBranch"),
                wire.get("worktree"),
                wire.get("worktreeBranch"),
                wire.get("executionDirectory"));
        var info = new io.haifa.agent.cli.remote.RemoteCodingClientInfo(
                "1",
                "host",
                new io.haifa.agent.project.domain.ProjectId("project-1"),
                "client-1",
                "local-user",
                java.util.Set.of(),
                java.util.Set.of(),
                java.util.Optional.of(metadata));
        var context = RemoteCodingTerminalRunner.workspaceContext(info);
        assertThat(context.workingDirectory())
                .isEqualTo("Host: " + directory.toAbsolutePath().normalize());
        assertThat(context.gitBranch()).contains("unavailable");
        var old = new io.haifa.agent.cli.remote.RemoteCodingClientInfo(
                "1", "host", info.projectId(), "client-1", "local-user", java.util.Set.of(), java.util.Set.of());
        assertThat(RemoteCodingTerminalRunner.workspaceContext(old).workingDirectory())
                .contains("Host workspace unavailable");
    }

    @Test
    void readsTheCurrentBranchFromARepositoryDirectory() throws Exception {
        Path git = Files.createDirectories(directory.resolve(".git"));
        Files.writeString(git.resolve("HEAD"), "ref: refs/heads/feat-terminal-context\n");

        assertThat(LocalGitBranchResolver.resolve(directory)).contains("feat-terminal-context");
        assertThat(CodingHostWorkspace.summary(directory))
                .containsEntry("gitBranch", "feat-terminal-context")
                .containsEntry(
                        "executionDirectory",
                        directory.toAbsolutePath().normalize().toString());
    }

    @Test
    void followsAWorktreeGitDirectoryPointerAndOmitsDetachedHead() throws Exception {
        Path metadata = Files.createDirectories(directory.resolve("metadata"));
        Files.writeString(directory.resolve(".git"), "gitdir: metadata\n");
        Files.writeString(metadata.resolve("HEAD"), "0123456789abcdef\n");

        assertThat(LocalGitBranchResolver.resolve(directory)).isEmpty();

        Files.writeString(metadata.resolve("HEAD"), "ref: refs/heads/feat/worktree\n");
        assertThat(LocalGitBranchResolver.resolve(directory)).contains("feat/worktree");
    }

    @Test
    void resolvesWorktreeDetailsWithMainProjectAndWorktreeBranch() throws Exception {
        Path mainRepo = Files.createDirectories(directory.resolve("main-repo"));
        Path mainGit = Files.createDirectories(mainRepo.resolve(".git"));
        Files.writeString(mainGit.resolve("HEAD"), "ref: refs/heads/main\n");

        Path worktree = Files.createDirectories(directory.resolve("worktree"));
        Path wtMetadata = Files.createDirectories(mainGit.resolve("worktrees").resolve("worktree"));
        Files.writeString(wtMetadata.resolve("commondir"), "../..\n");
        Files.writeString(wtMetadata.resolve("HEAD"), "ref: refs/heads/feat/worktree-branch\n");

        Files.writeString(worktree.resolve(".git"), "gitdir: " + wtMetadata.toAbsolutePath() + "\n");

        LocalGitBranchResolver.GitWorkspaceDetails details = LocalGitBranchResolver.resolveDetails(worktree);
        assertThat(details.projectPath())
                .isEqualTo(mainRepo.toAbsolutePath().normalize().toString());
        assertThat(details.gitBranch()).isEqualTo("main");
        assertThat(details.worktreePath())
                .isEqualTo(worktree.toAbsolutePath().normalize().toString());
        assertThat(details.worktreeBranch()).isEqualTo("feat/worktree-branch");
        assertThat(LocalGitBranchResolver.resolve(worktree)).contains("feat/worktree-branch");
        assertThat(CodingHostWorkspace.summary(worktree))
                .containsEntry("workingDirectory", mainRepo.toString())
                .containsEntry("worktree", worktree.toString())
                .containsEntry("worktreeBranch", "feat/worktree-branch")
                .containsEntry("executionDirectory", worktree.toString());
    }
}
