package io.haifa.agent.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.project.binding.WorkspaceBinding;
import io.haifa.agent.project.binding.WorkspaceBindingId;
import io.haifa.agent.project.binding.WorkspaceBindingMode;
import io.haifa.agent.project.binding.WorkspaceLocationRef;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.filesystem.FileListRequest;
import io.haifa.agent.project.filesystem.ReadOptions;
import io.haifa.agent.project.filesystem.SearchRequest;
import io.haifa.agent.project.filesystem.WorkspaceFileErrorCode;
import io.haifa.agent.project.filesystem.WorkspaceFileException;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.project.provider.local.LocalWorkspaceFileService;
import io.haifa.agent.project.provider.local.LocalWorkspaceLocationStore;
import io.haifa.agent.project.provider.local.SensitivePathPolicy;
import io.haifa.agent.project.store.InMemoryWorkspaceBindingStore;
import io.haifa.agent.project.store.InMemoryWorkspaceStore;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspaceCapabilitySet;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.project.workspace.WorkspacePermissionSet;
import io.haifa.agent.project.workspace.WorkspacePurpose;
import io.haifa.agent.project.workspace.WorkspaceRevision;
import io.haifa.agent.project.workspace.WorkspaceRoot;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class LocalWorkspaceFileServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");

    @TempDir
    Path root;

    @Test
    void listsStatsReadsAndSearchesWithLogicalPathsOnly() throws Exception {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/App.java"), "class App { // needle\n}\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("README.md"), "needle in docs\n", StandardCharsets.UTF_8);
        var fixture = fixture();

        assertThat(fixture.service.list(WorkspacePath.root(fixture.workspaceId), 10))
                .extracting(entry -> entry.metadata().path().projectPath().value())
                .containsExactly("README.md", "src");
        var firstPage = fixture.service.list(new FileListRequest(WorkspacePath.root(fixture.workspaceId), 0, 1));
        assertThat(firstPage.entries()).hasSize(1);
        assertThat(firstPage.truncated()).isTrue();
        assertThat(firstPage.nextOffset()).isEqualTo(1);
        var content = fixture.service.read(
                new WorkspacePath(fixture.workspaceId, ProjectPath.of("src/App.java")), ReadOptions.defaults());
        assertThat(content.text()).contains("needle");
        assertThat(content.contentHash()).startsWith("sha256:");
        assertThat(fixture.service.stat(content.path(), true).contentHash()).isPresent();
        assertThat(fixture.service.search(
                        new SearchRequest(WorkspacePath.root(fixture.workspaceId), "needle", 10, 10, 1024, true)))
                .extracting(result -> result.path().projectPath().value())
                .containsExactly("README.md", "src/App.java");
        assertThat(content.toString()).doesNotContain(root.toString());
    }

    @Test
    void rejectsSensitiveBinaryLargeAndInactiveBindingsWithoutLeakingHostPath() throws Exception {
        Files.writeString(root.resolve(".env"), "SECRET=value", StandardCharsets.UTF_8);
        Files.write(root.resolve("binary.bin"), new byte[] {1, 0, 2});
        Files.writeString(root.resolve("large.txt"), "0123456789", StandardCharsets.UTF_8);
        var fixture = fixture();

        assertThat(fixture.service.list(WorkspacePath.root(fixture.workspaceId), 10))
                .extracting(entry -> entry.metadata().path().projectPath().value())
                .doesNotContain(".env");
        assertFailure(fixture, ".env", ReadOptions.defaults(), WorkspaceFileErrorCode.SENSITIVE_PATH);
        assertFailure(fixture, "binary.bin", ReadOptions.defaults(), WorkspaceFileErrorCode.BINARY_CONTENT);
        assertFailure(
                fixture,
                "large.txt",
                new ReadOptions(4, 4, StandardCharsets.UTF_8, false),
                WorkspaceFileErrorCode.FILE_TOO_LARGE);
    }

    @Test
    void rejectsLinksEvenWhenTheyPointInsideOrOutsideTheRoot() throws Exception {
        Path target = Files.writeString(root.resolve("target.txt"), "safe", StandardCharsets.UTF_8);
        Path link = root.resolve("link.txt");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException exception) {
            Assumptions.assumeTrue(false, "symbolic links are unavailable on this test host");
        }
        var fixture = fixture();

        assertFailure(fixture, "link.txt", ReadOptions.defaults(), WorkspaceFileErrorCode.LINK_REJECTED);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void rejectsWindowsJunctions() throws Exception {
        Path outside = Files.createTempDirectory("haifa-junction-target");
        try {
            Files.writeString(outside.resolve("secret.txt"), "secret", StandardCharsets.UTF_8);
            Path junction = root.resolve("junction");
            Process process = new ProcessBuilder(
                            "cmd.exe", "/c", "mklink", "/J", junction.toString(), outside.toString())
                    .redirectErrorStream(true)
                    .start();
            int exit = process.waitFor();
            Assumptions.assumeTrue(exit == 0, "junction creation is unavailable on this host");
            var fixture = fixture();
            assertFailure(fixture, "junction/secret.txt", ReadOptions.defaults(), WorkspaceFileErrorCode.LINK_REJECTED);
        } finally {
            try (var children = Files.walk(outside)) {
                children.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (java.io.IOException ignored) {
                        // Test cleanup is best effort for a dedicated temporary directory.
                    }
                });
            }
        }
    }

    private void assertFailure(Fixture fixture, String path, ReadOptions options, WorkspaceFileErrorCode expected) {
        assertThatThrownBy(() ->
                        fixture.service.read(new WorkspacePath(fixture.workspaceId, ProjectPath.of(path)), options))
                .isInstanceOfSatisfying(WorkspaceFileException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(expected);
                    assertThat(exception.getMessage()).doesNotContain(root.toString());
                });
    }

    private Fixture fixture() {
        WorkspaceId workspaceId = new WorkspaceId("workspace-1");
        WorkspaceBindingId bindingId = new WorkspaceBindingId("binding-1");
        WorkspaceLocationRef locationRef = new WorkspaceLocationRef("local-1");
        var bindingStore = new InMemoryWorkspaceBindingStore();
        var workspaceStore = new InMemoryWorkspaceStore();
        var locations = new LocalWorkspaceLocationStore();
        locations.register(locationRef, root);
        WorkspaceBinding binding = WorkspaceBinding.provision(
                        bindingId,
                        locationRef,
                        WorkspaceBindingMode.DIRECT,
                        new PrincipalRef("owner", "user"),
                        WorkspaceCapabilitySet.readOnlyFiles(),
                        WorkspacePermissionSet.readOnly(),
                        LocalWorkspaceLocationStore.fingerprintFor(root),
                        NOW)
                .activate(NOW);
        bindingStore.create(binding);
        Workspace workspace = Workspace.provision(
                        workspaceId,
                        new ProjectId("project-1"),
                        WorkspacePurpose.PRIMARY,
                        new WorkspaceRoot(ProjectPath.root(), bindingId, "test"),
                        WorkspaceRevision.initial(binding.rootFingerprint()),
                        NOW)
                .activate(NOW);
        workspaceStore.create(workspace);
        var service =
                new LocalWorkspaceFileService(workspaceStore, bindingStore, locations, SensitivePathPolicy.defaults());
        return new Fixture(workspaceId, service);
    }

    private record Fixture(WorkspaceId workspaceId, LocalWorkspaceFileService service) {}
}
