package io.haifa.agent.project.hostworkspace;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.project.hostworkspace.scope.AuthorizedHostDirectory;
import io.haifa.agent.project.hostworkspace.scope.HostDirectoryPermission;
import io.haifa.agent.project.hostworkspace.scope.HostWorkspaceScope;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostRepositoryLocatorTest {
    @TempDir
    Path tempDir;

    private WorkspaceId workspaceId;
    private AuthorizedHostDirectory directory;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = tempDir.toRealPath();
        workspaceId = new WorkspaceId("workspace");
        directory = AuthorizedHostDirectory.of(workspaceId, tempDir, HostDirectoryPermission.READ_WRITE);
    }

    @Test
    void nearestRepositoryBoundaryWins() throws Exception {
        Path parentRepository = Files.createDirectories(tempDir.resolve("project-a"));
        Path nestedRepository = Files.createDirectories(parentRepository.resolve("nested-tool"));
        Path source = Files.createDirectories(nestedRepository.resolve("src")).resolve("Tool.java");
        Files.writeString(source, "class Tool {}");
        Set<Path> repositories = Set.of(parentRepository, nestedRepository);
        var locator = new HostRepositoryLocator((ignored, candidate) -> repositories.contains(candidate)
                ? HostGitInspectionStatus.WORKTREE_ROOT
                : HostGitInspectionStatus.NOT_WORKTREE_ROOT);

        var target = HostWorkspaceScope.initial(directory).resolve(source.toString());
        var located = locator.locate(target).orElseThrow();

        assertThat(located.hostRoot()).isEqualTo(nestedRepository);
        assertThat(located.workspaceRoot().workspaceId()).isEqualTo(workspaceId);
        assertThat(located.workspaceRoot().projectPath().value()).isEqualTo("project-a/nested-tool");
    }

    @Test
    void neverInspectsAboveAuthorizedDirectory() throws Exception {
        Path parent = Files.createDirectories(tempDir.resolve("parent"));
        Path allowed = Files.createDirectories(parent.resolve("allowed"));
        Path source = Files.createDirectories(allowed.resolve("src")).resolve("App.java");
        Files.writeString(source, "class App {}");
        var allowedDirectory = AuthorizedHostDirectory.of(workspaceId, allowed, HostDirectoryPermission.READ_WRITE);
        Set<Path> inspected = new HashSet<>();
        var locator = new HostRepositoryLocator((ignored, candidate) -> {
            inspected.add(candidate);
            return candidate.equals(parent)
                    ? HostGitInspectionStatus.WORKTREE_ROOT
                    : HostGitInspectionStatus.NOT_WORKTREE_ROOT;
        });

        var target = HostWorkspaceScope.initial(allowedDirectory).resolve(source.toString());

        assertThat(locator.locate(target)).isEmpty();
        assertThat(inspected).doesNotContain(parent);
        assertThat(inspected).contains(allowed);
    }

    @Test
    void invalidationObservesRepositoryCreatedDuringRun() throws Exception {
        Path source = Files.createDirectories(tempDir.resolve("src")).resolve("App.java");
        Files.writeString(source, "class App {}");
        Set<Path> repositories = new HashSet<>();
        var locator = new HostRepositoryLocator((ignored, candidate) -> repositories.contains(candidate)
                ? HostGitInspectionStatus.WORKTREE_ROOT
                : HostGitInspectionStatus.NOT_WORKTREE_ROOT);
        var target = HostWorkspaceScope.initial(directory).resolve(source.toString());

        assertThat(locator.locate(target)).isEmpty();
        repositories.add(tempDir);
        assertThat(locator.locate(target)).isEmpty();

        locator.invalidate(workspaceId);

        assertThat(locator.locate(target)).isPresent();
    }

    @Test
    void routesRootRepositoryAndTwoNestedRepositoriesIndependently() throws Exception {
        Path vendor = Files.createDirectories(tempDir.resolve("vendor/lib-x"));
        Path generator = Files.createDirectories(tempDir.resolve("tools/generator"));
        Set<Path> repositories = Set.of(tempDir, vendor, generator);
        var locator = new HostRepositoryLocator((ignored, candidate) -> repositories.contains(candidate)
                ? HostGitInspectionStatus.WORKTREE_ROOT
                : HostGitInspectionStatus.NOT_WORKTREE_ROOT);
        HostWorkspaceScope scope = HostWorkspaceScope.initial(directory);

        assertThat(locator.locate(scope.resolve(tempDir.resolve("src/App.java").toString()))
                        .orElseThrow()
                        .hostRoot())
                .isEqualTo(tempDir);
        assertThat(locator.locate(scope.resolve(vendor.resolve("src/Lib.java").toString()))
                        .orElseThrow()
                        .hostRoot())
                .isEqualTo(vendor);
        assertThat(locator.locate(scope.resolve(generator.resolve("Main.java").toString()))
                        .orElseThrow()
                        .hostRoot())
                .isEqualTo(generator);
    }
}
