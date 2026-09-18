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
    void readsTheCurrentBranchFromARepositoryDirectory() throws Exception {
        Path git = Files.createDirectories(directory.resolve(".git"));
        Files.writeString(git.resolve("HEAD"), "ref: refs/heads/feat-terminal-context\n");

        assertThat(LocalGitBranchResolver.resolve(directory)).contains("feat-terminal-context");
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
}
