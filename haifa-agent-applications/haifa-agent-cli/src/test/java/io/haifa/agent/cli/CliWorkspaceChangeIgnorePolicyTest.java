package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.project.filesystem.FileType;
import io.haifa.agent.project.path.ProjectPath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliWorkspaceChangeIgnorePolicyTest {

    @TempDir
    Path workspace;

    @Test
    void ignoresStandardGeneratedDirectoriesAndSimpleRootGitignoreDirectories() throws Exception {
        Files.writeString(workspace.resolve(".gitignore"), "local-tmp/\n/third-agent/\nsrc/generated/\n*.log\n");

        var policy = CliWorkspaceChangeIgnorePolicy.load(workspace);

        assertThat(ignored(policy, metadata("target", FileType.DIRECTORY))).isTrue();
        assertThat(ignored(policy, metadata("module/target/app.jar", FileType.FILE)))
                .isTrue();
        assertThat(ignored(policy, metadata("target", FileType.FILE))).isFalse();
        assertThat(ignored(policy, metadata("local-tmp/runtime/data.bin", FileType.FILE)))
                .isTrue();
        assertThat(ignored(policy, metadata("third-agent/repository.pack", FileType.FILE)))
                .isTrue();
        assertThat(ignored(policy, metadata("module/third-agent/repository.pack", FileType.FILE)))
                .isFalse();
        assertThat(ignored(policy, metadata("src/generated/output.txt", FileType.FILE)))
                .isTrue();
        assertThat(ignored(policy, metadata("src/main/App.java", FileType.FILE)))
                .isFalse();
        assertThat(policy.version()).startsWith("cli-workspace-change-v1-sha256-");
    }

    @Test
    void ignoresStandardPythonGeneratedDirectoriesAtAnyDepth() {
        var policy = CliWorkspaceChangeIgnorePolicy.load(workspace);

        assertThat(List.of(
                        ".pytest_cache/state",
                        "module/.mypy_cache/state",
                        "module/.ruff_cache/state",
                        ".tox/state",
                        "module/.venv/state",
                        "src/__pycache__/module.pyc"))
                .allMatch(path -> ignored(policy, metadata(path, FileType.FILE)));
    }

    @Test
    void doesNotApplyGitignoreRulesWhenNegationCouldReincludeTheirContents() throws Exception {
        Files.writeString(workspace.resolve(".gitignore"), "cache/\n!cache/keep.txt\n");

        var policy = CliWorkspaceChangeIgnorePolicy.load(workspace);

        assertThat(ignored(policy, metadata("cache/disposable.bin", FileType.FILE)))
                .isFalse();
        assertThat(ignored(policy, metadata("target/generated.bin", FileType.FILE)))
                .isTrue();
    }

    @Test
    void retainsUnrelatedDirectoryRulesWhenGitignoreContainsNegations() throws Exception {
        Files.writeString(workspace.resolve(".gitignore"), "cache/\nreports/\n!pom.xml\n!src/keep/\n");

        var policy = CliWorkspaceChangeIgnorePolicy.load(workspace);

        assertThat(ignored(policy, metadata("cache/disposable.bin", FileType.FILE)))
                .isTrue();
        assertThat(ignored(policy, metadata("reports/result.json", FileType.FILE)))
                .isTrue();
    }

    private static PathAndType metadata(String path, FileType type) {
        return new PathAndType(ProjectPath.of(path), type);
    }

    private static boolean ignored(CliWorkspaceChangeIgnorePolicy policy, PathAndType candidate) {
        return policy.ignores(candidate.path(), candidate.type());
    }

    private record PathAndType(ProjectPath path, FileType type) {}
}
