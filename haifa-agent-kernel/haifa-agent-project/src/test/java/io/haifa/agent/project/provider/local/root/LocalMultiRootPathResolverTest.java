package io.haifa.agent.project.provider.local.root;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.project.root.MultiRootPath;
import io.haifa.agent.project.root.WorkspaceRootAlias;
import io.haifa.agent.project.root.WorkspaceRootErrorCode;
import io.haifa.agent.project.root.WorkspaceRootException;
import io.haifa.agent.project.root.WorkspaceRootPermission;
import io.haifa.agent.project.root.WorkspaceRootStrategy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalMultiRootPathResolverTest {

    @TempDir
    Path tempDir;

    private Path mainDir;
    private Path docsDir;
    private LocalWorkspaceRootRegistry registry;

    @BeforeEach
    void setUp() throws IOException {
        mainDir = tempDir.resolve("main-repo");
        docsDir = tempDir.resolve("docs-repo");
        Files.createDirectories(mainDir);
        Files.createDirectories(docsDir);

        registry = LocalWorkspaceRootRegistry.builder()
                .addRoot(LocalWorkspaceRoot.of(
                        WorkspaceRootAlias.MAIN,
                        mainDir,
                        WorkspaceRootPermission.READ_WRITE,
                        WorkspaceRootStrategy.GIT,
                        false))
                .addRoot(LocalWorkspaceRoot.of(
                        WorkspaceRootAlias.of("docs"),
                        docsDir,
                        WorkspaceRootPermission.READ_ONLY,
                        WorkspaceRootStrategy.PLAIN,
                        false))
                .build();
    }

    @Test
    void parsesImplicitMainPath() {
        MultiRootPath parsed = LocalMultiRootPathResolver.parse("src/App.java");
        assertThat(parsed.rootAlias()).isEqualTo(WorkspaceRootAlias.MAIN);
        assertThat(parsed.relativePath()).isEqualTo("src/App.java");
    }

    @Test
    void parsesExplicitAliasPath() {
        MultiRootPath parsed = LocalMultiRootPathResolver.parse("docs:api/spec.json");
        assertThat(parsed.rootAlias()).isEqualTo(WorkspaceRootAlias.of("docs"));
        assertThat(parsed.relativePath()).isEqualTo("api/spec.json");
    }

    @Test
    void rejectsWindowsAbsoluteDrivePaths() {
        List<String> windowsPaths = List.of("D:/workspace/test", "C:\\Windows\\System32", "d:relative/path", "c:test");
        for (String path : windowsPaths) {
            assertThatThrownBy(() -> LocalMultiRootPathResolver.parse(path))
                    .isInstanceOf(WorkspaceRootException.class)
                    .satisfies(e -> {
                        WorkspaceRootException we = (WorkspaceRootException) e;
                        assertThat(we.code()).isEqualTo(WorkspaceRootErrorCode.ABSOLUTE_PATH_FORBIDDEN);
                    });
        }
    }

    @Test
    void rejectsPosixAbsolutePaths() {
        List<String> posixPaths = List.of("/etc/passwd", "/usr/local/bin", "\\var\\log");
        for (String path : posixPaths) {
            assertThatThrownBy(() -> LocalMultiRootPathResolver.parse(path))
                    .isInstanceOf(WorkspaceRootException.class)
                    .satisfies(e -> {
                        WorkspaceRootException we = (WorkspaceRootException) e;
                        assertThat(we.code()).isEqualTo(WorkspaceRootErrorCode.ABSOLUTE_PATH_FORBIDDEN);
                    });
        }
    }

    @Test
    void rejectsInvalidAliasSyntax() {
        assertThatThrownBy(() -> LocalMultiRootPathResolver.parse(":path/only"))
                .isInstanceOf(WorkspaceRootException.class)
                .satisfies(e -> assertThat(((WorkspaceRootException) e).code()).isEqualTo(WorkspaceRootErrorCode.INVALID_ROOT_ALIAS));

        assertThatThrownBy(() -> LocalMultiRootPathResolver.parse("invalid@alias:path"))
                .isInstanceOf(WorkspaceRootException.class)
                .satisfies(e -> assertThat(((WorkspaceRootException) e).code()).isEqualTo(WorkspaceRootErrorCode.INVALID_ROOT_ALIAS));
    }

    @Test
    void resolvesValidPathsUnderRoots() {
        var resolvedMain = LocalMultiRootPathResolver.resolve(registry, "main:src/App.java");
        assertThat(resolvedMain.root().alias()).isEqualTo(WorkspaceRootAlias.MAIN);
        assertThat(resolvedMain.hostPath()).isEqualTo(mainDir.resolve("src/App.java").normalize());

        var resolvedDocs = LocalMultiRootPathResolver.resolve(registry, "docs:overview.md");
        assertThat(resolvedDocs.root().alias()).isEqualTo(WorkspaceRootAlias.of("docs"));
        assertThat(resolvedDocs.hostPath()).isEqualTo(docsDir.resolve("overview.md").normalize());
    }

    @Test
    void rejectsDirectoryTraversalEscape() {
        assertThatThrownBy(() -> LocalMultiRootPathResolver.resolve(registry, "main:../../etc/passwd"))
                .isInstanceOf(WorkspaceRootException.class)
                .satisfies(e -> assertThat(((WorkspaceRootException) e).code()).isEqualTo(WorkspaceRootErrorCode.PATH_ESCAPE_FORBIDDEN));

        assertThatThrownBy(() -> LocalMultiRootPathResolver.resolve(registry, "docs:../secret.txt"))
                .isInstanceOf(WorkspaceRootException.class)
                .satisfies(e -> assertThat(((WorkspaceRootException) e).code()).isEqualTo(WorkspaceRootErrorCode.PATH_ESCAPE_FORBIDDEN));
    }

    @Test
    void rejectsUnknownRootAlias() {
        assertThatThrownBy(() -> LocalMultiRootPathResolver.resolve(registry, "unknown:file.txt"))
                .isInstanceOf(WorkspaceRootException.class)
                .satisfies(e -> assertThat(((WorkspaceRootException) e).code()).isEqualTo(WorkspaceRootErrorCode.ROOT_ALIAS_NOT_FOUND));
    }
}
