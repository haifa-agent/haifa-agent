package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.project.filesystem.FileMetadata;
import io.haifa.agent.project.filesystem.FileType;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliWorkspaceManifestIgnorePolicyTest {
    private static final WorkspaceId WORKSPACE_ID = new WorkspaceId("workspace-1");

    @TempDir
    Path workspace;

    @Test
    void ignoresStandardGeneratedDirectoriesAndSimpleRootGitignoreDirectories() throws Exception {
        Files.writeString(workspace.resolve(".gitignore"), "local-tmp/\n/third-agent/\nsrc/generated/\n*.log\n");

        var policy = CliWorkspaceManifestIgnorePolicy.load(workspace);

        assertThat(policy.ignores(metadata("target", FileType.DIRECTORY))).isTrue();
        assertThat(policy.ignores(metadata("module/target/app.jar", FileType.FILE)))
                .isTrue();
        assertThat(policy.ignores(metadata("target", FileType.FILE))).isFalse();
        assertThat(policy.ignores(metadata("local-tmp/runtime/data.bin", FileType.FILE)))
                .isTrue();
        assertThat(policy.ignores(metadata("third-agent/repository.pack", FileType.FILE)))
                .isTrue();
        assertThat(policy.ignores(metadata("module/third-agent/repository.pack", FileType.FILE)))
                .isFalse();
        assertThat(policy.ignores(metadata("src/generated/output.txt", FileType.FILE)))
                .isTrue();
        assertThat(policy.ignores(metadata("src/main/App.java", FileType.FILE))).isFalse();
        assertThat(policy.version()).startsWith("cli-workspace-manifest-v3-sha256-");
    }

    @Test
    void ignoresStandardPythonGeneratedDirectoriesAtAnyDepth() {
        var policy = CliWorkspaceManifestIgnorePolicy.load(workspace);

        assertThat(List.of(
                        ".pytest_cache/state",
                        "module/.mypy_cache/state",
                        "module/.ruff_cache/state",
                        ".tox/state",
                        "module/.venv/state",
                        "src/__pycache__/module.pyc"))
                .allMatch(path -> policy.ignores(metadata(path, FileType.FILE)));
    }

    @Test
    void doesNotApplyGitignoreRulesWhenNegationCouldReincludeTheirContents() throws Exception {
        Files.writeString(workspace.resolve(".gitignore"), "cache/\n!cache/keep.txt\n");

        var policy = CliWorkspaceManifestIgnorePolicy.load(workspace);

        assertThat(policy.ignores(metadata("cache/disposable.bin", FileType.FILE)))
                .isFalse();
        assertThat(policy.ignores(metadata("target/generated.bin", FileType.FILE)))
                .isTrue();
    }

    @Test
    void retainsUnrelatedDirectoryRulesWhenGitignoreContainsNegations() throws Exception {
        Files.writeString(workspace.resolve(".gitignore"), "cache/\nreports/\n!pom.xml\n!src/keep/\n");

        var policy = CliWorkspaceManifestIgnorePolicy.load(workspace);

        assertThat(policy.ignores(metadata("cache/disposable.bin", FileType.FILE)))
                .isTrue();
        assertThat(policy.ignores(metadata("reports/result.json", FileType.FILE)))
                .isTrue();
    }

    private static FileMetadata metadata(String path, FileType type) {
        return new FileMetadata(
                new WorkspacePath(WORKSPACE_ID, ProjectPath.of(path)),
                type,
                0,
                Optional.empty(),
                Optional.empty(),
                false);
    }
}
