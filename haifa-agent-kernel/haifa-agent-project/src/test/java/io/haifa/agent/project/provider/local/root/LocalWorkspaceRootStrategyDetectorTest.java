package io.haifa.agent.project.provider.local.root;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.project.root.WorkspaceRootStrategy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalWorkspaceRootStrategyDetectorTest {

    @TempDir
    Path tempDir;

    private LocalWorkspaceRootStrategyDetector detector;

    @BeforeEach
    void setUp() {
        detector = new LocalWorkspaceRootStrategyDetector();
    }

    @Test
    void detectsStandardGitDirectory() throws IOException {
        Path repo = tempDir.resolve("git-repo");
        Path gitDir = repo.resolve(".git");
        Files.createDirectories(gitDir);
        Files.writeString(gitDir.resolve("HEAD"), "ref: refs/heads/main\n");

        var result = detector.detect(repo);
        assertThat(result.strategy()).isEqualTo(WorkspaceRootStrategy.GIT);
    }

    @Test
    void detectsGitWorktreeOrSubmoduleFile() throws IOException {
        Path repo = tempDir.resolve("submodule-repo");
        Files.createDirectories(repo);
        Files.writeString(repo.resolve(".git"), "gitdir: ../../.git/modules/sub\n");

        var result = detector.detect(repo);
        assertThat(result.strategy()).isEqualTo(WorkspaceRootStrategy.GIT);
    }

    @Test
    void detectsPlainDirectoryWhenNoGitPresent() throws IOException {
        Path plainDir = tempDir.resolve("plain-dir");
        Files.createDirectories(plainDir);
        Files.writeString(plainDir.resolve("README.md"), "# Plain\n");

        var result = detector.detect(plainDir);
        assertThat(result.strategy()).isEqualTo(WorkspaceRootStrategy.PLAIN);
    }

    @Test
    void detectsPlainWhenDirectoryDoesNotExist() {
        Path nonExistent = tempDir.resolve("does-not-exist");
        var result = detector.detect(nonExistent);
        assertThat(result.strategy()).isEqualTo(WorkspaceRootStrategy.PLAIN);
    }
}
