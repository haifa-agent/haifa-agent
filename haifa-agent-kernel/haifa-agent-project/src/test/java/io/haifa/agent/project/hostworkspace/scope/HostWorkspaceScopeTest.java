package io.haifa.agent.project.hostworkspace.scope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class HostWorkspaceScopeTest {
    @TempDir
    Path tempDir;

    private Path rootA;
    private Path rootB;
    private Path outside;
    private HostWorkspaceScope scope;

    @BeforeEach
    void setUp() throws IOException {
        // @TempDir may use an OS alias (/var vs /private/var or a Windows short path).
        // Scope inputs must be derived from the same canonical host root used for authorization.
        tempDir = tempDir.toRealPath();
        rootA = Files.createDirectories(tempDir.resolve("project-a"));
        rootB = Files.createDirectories(tempDir.resolve("project-b"));
        outside = Files.createDirectories(tempDir.resolve("outside"));
        scope = HostWorkspaceScope.initial(AuthorizedHostDirectory.of(
                new WorkspaceId("ws-a"), rootA.toRealPath(), HostDirectoryPermission.READ_WRITE));
        scope = scope.withDirectory(AuthorizedHostDirectory.of(
                new WorkspaceId("ws-b"), rootB.toRealPath(), HostDirectoryPermission.READ_ONLY));
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
                .isInstanceOfSatisfying(HostWorkspaceScopeException.class, exception -> assertThat(exception.code())
                        .isEqualTo(HostWorkspaceScopeErrorCode.INVALID_ARGUMENT));
        assertThatThrownBy(() -> scope.resolve(null)).isInstanceOf(HostWorkspaceScopeException.class);
    }

    @Test
    void rejectsRelativePathsWithoutAliasFallback() {
        assertThatThrownBy(() -> scope.resolve("src/App.java"))
                .isInstanceOfSatisfying(HostWorkspaceScopeException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(HostWorkspaceScopeErrorCode.INVALID_ARGUMENT);
                    assertThat(exception.getMessage()).contains("absolute");
                });
    }

    @Test
    void rejectsRootAliasSyntax() {
        for (String input : List.of("main:src/App.java", "docs:guide.md", "main:.")) {
            assertThatThrownBy(() -> scope.resolve(input))
                    .isInstanceOfSatisfying(HostWorkspaceScopeException.class, exception -> {
                        assertThat(exception.code()).isEqualTo(HostWorkspaceScopeErrorCode.INVALID_ARGUMENT);
                        assertThat(exception.getMessage()).contains("absolute").doesNotContain("main:", "docs:");
                    });
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void rejectsDriveRelativePathsOnWindows() {
        String drive = rootA.toString().substring(0, 2);
        assertThatThrownBy(() -> scope.resolve(drive + "relative.txt"))
                .isInstanceOfSatisfying(HostWorkspaceScopeException.class, exception -> assertThat(exception.code())
                        .isEqualTo(HostWorkspaceScopeErrorCode.INVALID_ARGUMENT));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void rejectsPosixAbsolutePathsOutsideScope() {
        assertThatThrownBy(() -> scope.resolve("/etc/hostname"))
                .isInstanceOfSatisfying(HostWorkspaceScopeException.class, exception -> assertThat(exception.code())
                        .isEqualTo(HostWorkspaceScopeErrorCode.ACCESS_DENIED));
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
                .isInstanceOfSatisfying(HostWorkspaceScopeException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(HostWorkspaceScopeErrorCode.ACCESS_DENIED);
                    assertThat(exception.path()).isEqualTo(stranger.toString());
                });
    }

    @Test
    void collapsesParentTraversalBeforeAuthorization() {
        Path escaped = Path.of(rootA.toString(), "..", "outside", "secret.txt");

        assertThatThrownBy(() -> scope.resolve(escaped.toString()))
                .isInstanceOfSatisfying(HostWorkspaceScopeException.class, exception -> assertThat(exception.code())
                        .isEqualTo(HostWorkspaceScopeErrorCode.ACCESS_DENIED));
        String innerTraversal =
                rootA.resolve("sub") + java.io.File.separator + ".." + java.io.File.separator + "App.java";
        assertThatCode(() -> scope.resolve(innerTraversal)).doesNotThrowAnyException();
    }

    @Test
    void requiresWritePermissionForReadOnlyDirectories() {
        assertThatThrownBy(
                        () -> scope.requireWritable(scope.allowedDirectories().get(1)))
                .isInstanceOfSatisfying(HostWorkspaceScopeException.class, exception -> assertThat(exception.code())
                        .isEqualTo(HostWorkspaceScopeErrorCode.PERMISSION_DENIED));
        assertThatCode(() -> scope.requireWritable(scope.allowedDirectories().get(0)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsExistingSymlinkTargetEscapingTheAuthorizedDirectory() throws IOException {
        Path secret = Files.writeString(outside.resolve("secret.txt"), "secret", StandardCharsets.UTF_8);
        Path link = createSymbolicLinkOrSkip(rootA.resolve("link.txt"), secret);

        assertThatThrownBy(() -> scope.resolve(link.toString()))
                .isInstanceOfSatisfying(HostWorkspaceScopeException.class, exception -> assertThat(exception.code())
                        .isEqualTo(HostWorkspaceScopeErrorCode.PATH_ESCAPE_DENIED));
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
                .isInstanceOfSatisfying(HostWorkspaceScopeException.class, exception -> assertThat(exception.code())
                        .isEqualTo(HostWorkspaceScopeErrorCode.PATH_ESCAPE_DENIED));
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
                    .isInstanceOfSatisfying(HostWorkspaceScopeException.class, exception -> assertThat(exception.code())
                            .isEqualTo(HostWorkspaceScopeErrorCode.PATH_ESCAPE_DENIED));
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
        AuthorizedHostDirectory parent = AuthorizedHostDirectory.of(
                new WorkspaceId("ws-parent"), rootA.toRealPath(), HostDirectoryPermission.READ_WRITE);
        AuthorizedHostDirectory child = AuthorizedHostDirectory.of(
                new WorkspaceId("ws-child"), rootA.resolve("child").toRealPath(), HostDirectoryPermission.READ_ONLY);

        assertThatThrownBy(() -> HostWorkspaceScope.initial(child).withDirectory(parent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Overlapping");
        assertThatThrownBy(() -> HostWorkspaceScope.initial(parent).withDirectory(child))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HostWorkspaceScope.initial(parent)
                        .withDirectory(AuthorizedHostDirectory.of(
                                new WorkspaceId("ws-copy"), parent.realPath(), HostDirectoryPermission.READ_ONLY)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsDisjointSiblingDirectoriesIncludingAcrossDifferentParents() throws IOException {
        AuthorizedHostDirectory sibling = AuthorizedHostDirectory.of(
                new WorkspaceId("ws-outside"), outside.toRealPath(), HostDirectoryPermission.READ_WRITE);

        assertThatCode(() -> HostWorkspaceScope.initial(
                                scope.allowedDirectories().get(0))
                        .withDirectory(sibling))
                .doesNotThrowAnyException();
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void resolvesPeerAuthorizedDirectoriesAcrossTwoActualWindowsVolumes() throws IOException {
        List<Path> candidateBases = new ArrayList<>();
        candidateBases.add(tempDir);
        candidateBases.add(
                Path.of(System.getProperty("user.dir")).resolve("target").toAbsolutePath());
        for (String name : List.of("RUNNER_TEMP", "GITHUB_WORKSPACE")) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank())
                candidateBases.add(Path.of(value).toAbsolutePath());
        }
        LinkedHashMap<Path, Path> basesByVolume = new LinkedHashMap<>();
        for (Path candidate : candidateBases) {
            try {
                Files.createDirectories(candidate);
                Path canonical = candidate.toRealPath();
                basesByVolume.putIfAbsent(canonical.getRoot(), canonical);
            } catch (IOException ignored) {
                // A CI-provided candidate may not be writable by this process.
            }
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(
                basesByVolume.size() >= 2, "two writable Windows volumes are unavailable");
        var bases = basesByVolume.values().iterator();
        Path first = Files.createTempDirectory(bases.next(), "haifa-plan030-volume-a-");
        Path second = Files.createTempDirectory(bases.next(), "haifa-plan030-volume-b-");
        try {
            Path firstFile = Files.writeString(first.resolve("same.txt"), "a", StandardCharsets.UTF_8);
            Path secondFile = Files.writeString(second.resolve("same.txt"), "b", StandardCharsets.UTF_8);
            HostWorkspaceScope crossVolumeScope = new HostWorkspaceScope(
                    List.of(
                            AuthorizedHostDirectory.of(
                                    new WorkspaceId("ws-volume-a"),
                                    first.toRealPath(),
                                    HostDirectoryPermission.READ_WRITE),
                            AuthorizedHostDirectory.of(
                                    new WorkspaceId("ws-volume-b"),
                                    second.toRealPath(),
                                    HostDirectoryPermission.READ_WRITE)),
                    1L);

            assertThat(crossVolumeScope
                            .resolve(firstFile.toString())
                            .workspacePath()
                            .workspaceId())
                    .isEqualTo(new WorkspaceId("ws-volume-a"));
            assertThat(crossVolumeScope
                            .resolve(secondFile.toString())
                            .workspacePath()
                            .workspaceId())
                    .isEqualTo(new WorkspaceId("ws-volume-b"));
        } finally {
            deleteRecursively(first);
            deleteRecursively(second);
        }
    }

    @Test
    void versionAdvancesWhenTheDirectorySetChanges() throws IOException {
        AuthorizedHostDirectory third = AuthorizedHostDirectory.of(
                new WorkspaceId("ws-c"), outside.toRealPath(), HostDirectoryPermission.READ_WRITE);

        HostWorkspaceScope expanded = scope.withDirectory(third);
        HostWorkspaceScope reverted = expanded.withoutDirectory(new WorkspaceId("ws-c"));

        assertThat(expanded.version()).isEqualTo(scope.version() + 1);
        assertThat(reverted.version()).isEqualTo(expanded.version() + 1);
        assertThat(reverted.allowedDirectories()).isEqualTo(scope.allowedDirectories());
        assertThat(expanded.withoutDirectory(new WorkspaceId("ws-unknown"))).isSameAs(expanded);
    }

    @Test
    void rejectsEmptyScope() {
        HostWorkspaceScope single =
                HostWorkspaceScope.initial(scope.allowedDirectories().get(0));

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
