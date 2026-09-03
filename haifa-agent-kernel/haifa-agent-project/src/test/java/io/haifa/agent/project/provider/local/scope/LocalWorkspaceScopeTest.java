package io.haifa.agent.project.provider.local.scope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class LocalWorkspaceScopeTest {
    @TempDir
    Path tempDir;

    private Path rootA;
    private Path rootB;
    private Path outside;
    private LocalWorkspaceScope scope;

    @BeforeEach
    void setUp() throws IOException {
        rootA = Files.createDirectories(tempDir.resolve("project-a"));
        rootB = Files.createDirectories(tempDir.resolve("project-b"));
        outside = Files.createDirectories(tempDir.resolve("outside"));
        scope = LocalWorkspaceScope.initial(LocalAllowedDirectory.of(
                new WorkspaceId("ws-a"), rootA.toRealPath(), LocalDirectoryPermission.READ_WRITE));
        scope = scope.withDirectory(LocalAllowedDirectory.of(
                new WorkspaceId("ws-b"), rootB.toRealPath(), LocalDirectoryPermission.READ_ONLY));
    }

    @AfterEach
    void tearDown() throws IOException {
        deleteRecursively(outside);
    }

    @Test
    void resolvesAbsolutePathInsideAuthorizedDirectory() throws IOException {
        Files.createDirectories(rootA.resolve("src"));
        Path file = Files.writeString(rootA.resolve("src/App.java").toAbsolutePath(), "hello", StandardCharsets.UTF_8);

        ResolvedAuthorizedPath resolved = scope.resolve(file.toString());

        assertThat(resolved.directory().workspaceId()).isEqualTo(new WorkspaceId("ws-a"));
        assertThat(resolved.workspacePath().workspaceId()).isEqualTo(new WorkspaceId("ws-a"));
        assertThat(resolved.workspacePath().projectPath()).isEqualTo(ProjectPath.of("src/App.java"));
        assertThat(resolved.hostPath()).isEqualTo(file.toRealPath());
        assertThat(resolved.absoluteInput()).isEqualTo(file.toString());
    }

    @Test
    void mapsDirectoryRootToLogicalRoot() throws IOException {
        ResolvedAuthorizedPath resolved = scope.resolve(rootA.toString() + "/");

        assertThat(resolved.workspacePath().projectPath()).isEqualTo(ProjectPath.root());
        assertThat(resolved.hostPath()).isEqualTo(rootA.toRealPath());
    }

    @Test
    void twoDirectoriesOwningTheSameRelativeFileNameStayDistinct() throws IOException {
        Files.writeString(rootA.resolve("notes.md"), "in a", StandardCharsets.UTF_8);
        Files.writeString(rootB.resolve("notes.md"), "in b", StandardCharsets.UTF_8);

        ResolvedAuthorizedPath fromA = scope.resolve(rootA.resolve("notes.md").toString());
        ResolvedAuthorizedPath fromB = scope.resolve(rootB.resolve("notes.md").toString());

        assertThat(fromA.workspacePath().workspaceId()).isEqualTo(new WorkspaceId("ws-a"));
        assertThat(fromB.workspacePath().workspaceId()).isEqualTo(new WorkspaceId("ws-b"));
        assertThat(fromA.workspacePath().projectPath())
                .isEqualTo(fromB.workspacePath().projectPath());
        assertThat(fromA.workspacePath()).isNotEqualTo(fromB.workspacePath());
    }

    @Test
    void rejectsBlankInput() {
        assertThatThrownBy(() -> scope.resolve(" "))
                .isInstanceOfSatisfying(LocalWorkspaceScopeException.class, exception -> assertThat(exception.code())
                        .isEqualTo(LocalScopeErrorCode.INVALID_ARGUMENT));
        assertThatThrownBy(() -> scope.resolve(null)).isInstanceOf(LocalWorkspaceScopeException.class);
    }

    @Test
    void rejectsRelativePathsWithoutAliasFallback() {
        assertThatThrownBy(() -> scope.resolve("src/App.java"))
                .isInstanceOfSatisfying(LocalWorkspaceScopeException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(LocalScopeErrorCode.INVALID_ARGUMENT);
                    assertThat(exception.getMessage()).contains("absolute");
                });
    }

    @Test
    void rejectsRootAliasSyntax() {
        for (String input : List.of("main:src/App.java", "docs:guide.md", "main:.")) {
            assertThatThrownBy(() -> scope.resolve(input))
                    .isInstanceOfSatisfying(
                            LocalWorkspaceScopeException.class,
                            exception -> assertThat(exception.code()).isEqualTo(LocalScopeErrorCode.INVALID_ARGUMENT));
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void rejectsDriveRelativePathsOnWindows() {
        String drive = rootA.toString().substring(0, 2);
        assertThatThrownBy(() -> scope.resolve(drive + "relative.txt"))
                .isInstanceOfSatisfying(LocalWorkspaceScopeException.class, exception -> assertThat(exception.code())
                        .isEqualTo(LocalScopeErrorCode.INVALID_ARGUMENT));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void rejectsPosixAbsolutePathsOutsideScope() {
        assertThatThrownBy(() -> scope.resolve("/etc/hostname"))
                .isInstanceOfSatisfying(LocalWorkspaceScopeException.class, exception -> assertThat(exception.code())
                        .isEqualTo(LocalScopeErrorCode.ACCESS_DENIED));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void resolvesPosixAbsolutePathsInsideAuthorizedDirectory() throws IOException {
        Path file = Files.writeString(rootA.resolve("posix.txt"), "ok", StandardCharsets.UTF_8);

        ResolvedAuthorizedPath resolved = scope.resolve(file.toString());

        assertThat(resolved.workspacePath().projectPath()).isEqualTo(ProjectPath.of("posix.txt"));
    }

    @Test
    void rejectsUnauthorizedAbsolutePaths() {
        Path stranger = outside.resolve("stranger.txt");

        assertThatThrownBy(() -> scope.resolve(stranger.toString()))
                .isInstanceOfSatisfying(LocalWorkspaceScopeException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(LocalScopeErrorCode.ACCESS_DENIED);
                    assertThat(exception.path()).isEqualTo(stranger.toString());
                });
    }

    @Test
    void collapsesParentTraversalBeforeAuthorization() {
        Path escaped = Path.of(rootA.toString(), "..", "outside", "secret.txt");

        assertThatThrownBy(() -> scope.resolve(escaped.toString()))
                .isInstanceOfSatisfying(LocalWorkspaceScopeException.class, exception -> assertThat(exception.code())
                        .isEqualTo(LocalScopeErrorCode.ACCESS_DENIED));
        String innerTraversal =
                rootA.resolve("sub") + java.io.File.separator + ".." + java.io.File.separator + "App.java";
        assertThatCode(() -> scope.resolve(innerTraversal)).doesNotThrowAnyException();
    }

    @Test
    void requiresWritePermissionForReadOnlyDirectories() {
        assertThatThrownBy(
                        () -> scope.requireWritable(scope.allowedDirectories().get(1)))
                .isInstanceOfSatisfying(LocalWorkspaceScopeException.class, exception -> assertThat(exception.code())
                        .isEqualTo(LocalScopeErrorCode.PERMISSION_DENIED));
        assertThatCode(() -> scope.requireWritable(scope.allowedDirectories().get(0)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsExistingSymlinkTargetEscapingTheAuthorizedDirectory() throws IOException {
        Path secret = Files.writeString(outside.resolve("secret.txt"), "secret", StandardCharsets.UTF_8);
        Path link = createSymbolicLinkOrSkip(rootA.resolve("link.txt"), secret);

        assertThatThrownBy(() -> scope.resolve(link.toString()))
                .isInstanceOfSatisfying(LocalWorkspaceScopeException.class, exception -> assertThat(exception.code())
                        .isEqualTo(LocalScopeErrorCode.PATH_ESCAPE_DENIED));
    }

    @Test
    void acceptsSymlinkThatStaysInsideTheAuthorizedDirectory() throws IOException {
        Path target = Files.writeString(rootA.resolve("target.txt"), "safe", StandardCharsets.UTF_8);
        Path link = createSymbolicLinkOrSkip(rootA.resolve("inside-link.txt"), target);

        ResolvedAuthorizedPath resolved = scope.resolve(link.toString());

        assertThat(resolved.hostPath()).isEqualTo(target.toRealPath());
        assertThat(resolved.workspacePath().projectPath()).isEqualTo(ProjectPath.of("target.txt"));
    }

    @Test
    void rejectsNewFileWhoseParentEscapesViaSymlink() throws IOException {
        Path linkDir = createSymbolicLinkOrSkip(rootA.resolve("link-dir"), outside);

        assertThatThrownBy(() -> scope.resolve(linkDir.resolve("new-file.txt").toString()))
                .isInstanceOfSatisfying(LocalWorkspaceScopeException.class, exception -> assertThat(exception.code())
                        .isEqualTo(LocalScopeErrorCode.PATH_ESCAPE_DENIED));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void rejectsWindowsJunctionEscapingTheAuthorizedDirectory() throws Exception {
        Path junctionTarget = Files.createTempDirectory("haifa-scope-junction-target");
        try {
            Files.writeString(junctionTarget.resolve("secret.txt"), "secret", StandardCharsets.UTF_8);
            Path junction = rootA.resolve("junction");
            Process process = new ProcessBuilder(
                            "cmd.exe", "/c", "mklink", "/J", junction.toString(), junctionTarget.toString())
                    .redirectErrorStream(true)
                    .start();
            int exit = process.waitFor();
            org.junit.jupiter.api.Assumptions.assumeTrue(exit == 0, "junction creation is unavailable on this host");

            assertThatThrownBy(
                            () -> scope.resolve(junction.resolve("secret.txt").toString()))
                    .isInstanceOfSatisfying(
                            LocalWorkspaceScopeException.class, exception -> assertThat(exception.code())
                                    .isEqualTo(LocalScopeErrorCode.PATH_ESCAPE_DENIED));
        } finally {
            deleteRecursively(junctionTarget);
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void matchesAuthorizedDirectoryCaseInsensitivelyOnWindows() throws IOException {
        Path differentlyCased =
                Path.of(rootA.toString().toUpperCase(Locale.ROOT)).resolve("App.java");

        ResolvedAuthorizedPath resolved = scope.resolve(differentlyCased.toString());

        assertThat(resolved.directory().workspaceId()).isEqualTo(new WorkspaceId("ws-a"));
    }

    @Test
    void rejectsOverlappingAuthorizedDirectoriesAtConstruction() throws IOException {
        Files.createDirectories(rootA.resolve("child"));
        LocalAllowedDirectory parent = LocalAllowedDirectory.of(
                new WorkspaceId("ws-parent"), rootA.toRealPath(), LocalDirectoryPermission.READ_WRITE);
        LocalAllowedDirectory child = LocalAllowedDirectory.of(
                new WorkspaceId("ws-child"), rootA.resolve("child").toRealPath(), LocalDirectoryPermission.READ_ONLY);

        assertThatThrownBy(() -> LocalWorkspaceScope.initial(child).withDirectory(parent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Overlapping");
        assertThatThrownBy(() -> LocalWorkspaceScope.initial(parent).withDirectory(child))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LocalWorkspaceScope.initial(parent)
                        .withDirectory(LocalAllowedDirectory.of(
                                new WorkspaceId("ws-copy"), parent.realPath(), LocalDirectoryPermission.READ_ONLY)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsDisjointSiblingDirectoriesIncludingAcrossDifferentParents() throws IOException {
        LocalAllowedDirectory sibling = LocalAllowedDirectory.of(
                new WorkspaceId("ws-outside"), outside.toRealPath(), LocalDirectoryPermission.READ_WRITE);

        assertThatCode(() -> LocalWorkspaceScope.initial(
                                scope.allowedDirectories().get(0))
                        .withDirectory(sibling))
                .doesNotThrowAnyException();
    }

    @Test
    void versionAdvancesWhenTheDirectorySetChanges() throws IOException {
        LocalAllowedDirectory third = LocalAllowedDirectory.of(
                new WorkspaceId("ws-c"), outside.toRealPath(), LocalDirectoryPermission.READ_WRITE);

        LocalWorkspaceScope expanded = scope.withDirectory(third);
        LocalWorkspaceScope reverted = expanded.withoutDirectory(new WorkspaceId("ws-c"));

        assertThat(expanded.version()).isEqualTo(scope.version() + 1);
        assertThat(reverted.version()).isEqualTo(expanded.version() + 1);
        assertThat(reverted.allowedDirectories()).isEqualTo(scope.allowedDirectories());
        assertThat(expanded.withoutDirectory(new WorkspaceId("ws-unknown"))).isSameAs(expanded);
    }

    @Test
    void rejectsEmptyScope() {
        LocalWorkspaceScope single =
                LocalWorkspaceScope.initial(scope.allowedDirectories().get(0));

        assertThatThrownBy(() -> single.withoutDirectory(
                        scope.allowedDirectories().get(0).workspaceId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be empty");
    }

    private Path createSymbolicLinkOrSkip(Path link, Path target) throws IOException {
        try {
            return Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException exception) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symbolic links are unavailable on this test host");
            return link;
        }
    }

    private void deleteRecursively(Path directory) throws IOException {
        try (var children = Files.walk(directory)) {
            children.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Test cleanup is best effort for a dedicated temporary directory.
                }
            });
        }
    }
}
