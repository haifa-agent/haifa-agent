package io.haifa.agent.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.project.binding.WorkspaceBinding;
import io.haifa.agent.project.binding.WorkspaceBindingId;
import io.haifa.agent.project.binding.WorkspaceBindingMode;
import io.haifa.agent.project.binding.WorkspaceLocationRef;
import io.haifa.agent.project.changeset.FileChange;
import io.haifa.agent.project.changeset.FileChangeSet;
import io.haifa.agent.project.changeset.FileChangeSetId;
import io.haifa.agent.project.changeset.FileVersion;
import io.haifa.agent.project.configuration.InMemoryProjectConfigurationStore;
import io.haifa.agent.project.configuration.ProjectConfiguration;
import io.haifa.agent.project.configuration.ProjectConfigurationId;
import io.haifa.agent.project.configuration.ProjectConfigurationVersion;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.filesystem.FileType;
import io.haifa.agent.project.index.IndexStatus;
import io.haifa.agent.project.index.ProjectIndexService;
import io.haifa.agent.project.index.file.FileIndexQuery;
import io.haifa.agent.project.path.ProjectPath;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectIndexTest {
    private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");

    @TempDir
    Path root;

    @Test
    void atomicallyBuildsAndIncrementallyUpdatesFileSymbolAndDocumentIndexes() throws Exception {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(
                root.resolve("src/App.java"), "package demo;\npublic class App {\n  public void run() {}\n}\n");
        Files.writeString(root.resolve("README.md"), "# Title\nIntro\n```\n# Not heading\n```\n# Title\nAgain\n");
        Files.createDirectories(root.resolve("target"));
        Files.writeString(root.resolve("target/ignored.txt"), "ignored");
        Fixture fixture = fixture();

        assertThat(fixture.index.rebuild(fixture.workspace.id()).value()).isEqualTo(1);
        var files = fixture.index.queryFiles(new FileIndexQuery(fixture.workspace.id(), ProjectPath.root(), "", 0, 20));
        assertThat(files.entries())
                .extracting(entry -> entry.path().value())
                .contains("src/App.java", "README.md")
                .doesNotContain("target", "target/ignored.txt");
        assertThat(fixture.index.querySymbols(fixture.workspace.id(), "app", 10))
                .extracting(symbol -> symbol.qualifiedName())
                .contains("demo.App");
        assertThat(fixture.index.querySymbols(fixture.workspace.id(), "run", 10))
                .extracting(symbol -> symbol.name())
                .contains("run");
        assertThat(fixture.index.queryDocuments(fixture.workspace.id(), "title", 10))
                .extracting(node -> node.nodeId())
                .containsExactly("README.md#title", "README.md#title-2");

        String appHash = fixture.files
                .stat(
                        new io.haifa.agent.project.path.WorkspacePath(
                                fixture.workspace.id(), ProjectPath.of("src/App.java")),
                        true)
                .contentHash()
                .orElseThrow();
        Files.delete(root.resolve("src/App.java"));
        Files.writeString(root.resolve("src/NewType.java"), "package demo;\nrecord NewType(String value) {}\n");
        String newHash = fixture.files
                .stat(
                        new io.haifa.agent.project.path.WorkspacePath(
                                fixture.workspace.id(), ProjectPath.of("src/NewType.java")),
                        true)
                .contentHash()
                .orElseThrow();
        Files.move(root.resolve("README.md"), root.resolve("GUIDE.md"));
        String markdownHash = fixture.files
                .stat(
                        new io.haifa.agent.project.path.WorkspacePath(
                                fixture.workspace.id(), ProjectPath.of("GUIDE.md")),
                        true)
                .contentHash()
                .orElseThrow();
        WorkspaceRevision revision = new WorkspaceRevision(1, "index-change");
        FileChangeSet changes = FileChangeSet.pending(
                        new FileChangeSetId("changes-1"),
                        fixture.workspace.projectId(),
                        fixture.workspace.id(),
                        "operation-1",
                        "run-1",
                        "tool-1",
                        fixture.workspace.revision(),
                        new PrincipalRef("actor", "user"),
                        "allow-1",
                        NOW)
                .applied(
                        revision,
                        List.of(
                                new FileChange(
                                        io.haifa.agent.project.changeset.FileChangeType.DELETE,
                                        ProjectPath.of("src/App.java"),
                                        null,
                                        new FileVersion(FileType.FILE, 57, appHash),
                                        null),
                                new FileChange(
                                        io.haifa.agent.project.changeset.FileChangeType.CREATE,
                                        ProjectPath.of("src/NewType.java"),
                                        null,
                                        null,
                                        new FileVersion(FileType.FILE, 50, newHash)),
                                new FileChange(
                                        io.haifa.agent.project.changeset.FileChangeType.MOVE,
                                        ProjectPath.of("README.md"),
                                        ProjectPath.of("GUIDE.md"),
                                        new FileVersion(FileType.FILE, 58, markdownHash),
                                        new FileVersion(FileType.FILE, 58, markdownHash))),
                        true,
                        NOW);

        assertThat(fixture.index.apply(changes).value()).isEqualTo(2);
        assertThat(fixture.index.querySymbols(fixture.workspace.id(), "NewType", 10))
                .extracting(symbol -> symbol.name())
                .contains("NewType");
        assertThat(fixture.index.querySymbols(fixture.workspace.id(), "App", 10))
                .isEmpty();
        assertThat(fixture.index.queryDocuments(fixture.workspace.id(), "title", 10))
                .extracting(node -> node.path().value())
                .containsOnly("GUIDE.md");

        fixture.index.markSuspect(fixture.workspace.id());
        assertThat(fixture.index.status(fixture.workspace.id())).isEqualTo(IndexStatus.SUSPECT);
        assertThat(fixture.index.rebuild(fixture.workspace.id()).value()).isEqualTo(3);
        assertThat(fixture.index.status(fixture.workspace.id())).isEqualTo(IndexStatus.READY);
    }

    @Test
    void configurationVersionsAreImmutableAndContentAddressed() {
        var store = new InMemoryProjectConfigurationStore();
        var configuration = ProjectConfiguration.create(
                new ProjectConfigurationId("config-1"),
                new ProjectConfigurationVersion("1"),
                new WorkspaceId("workspace-1"),
                "coding",
                "1",
                Set.of("file.read"),
                Set.of("project.workspace.files"),
                Set.of("file.read"),
                "policy-1");
        store.publish(configuration);
        assertThat(configuration.digest()).startsWith("sha256:");
        assertThat(store.find(configuration.id(), configuration.version())).contains(configuration);
        var changed = ProjectConfiguration.create(
                configuration.id(),
                configuration.version(),
                configuration.defaultWorkspaceId(),
                "coding",
                "1",
                Set.of("file.read", "file.write"),
                configuration.contextSources(),
                configuration.tools(),
                "policy-1");
        assertThatThrownBy(() -> store.publish(changed)).isInstanceOf(IllegalStateException.class);
    }

    private Fixture fixture() {
        var workspaces = new InMemoryWorkspaceStore();
        var bindings = new InMemoryWorkspaceBindingStore();
        var locations = new LocalWorkspaceLocationStore();
        WorkspaceId workspaceId = new WorkspaceId("workspace-1");
        WorkspaceBindingId bindingId = new WorkspaceBindingId("binding-1");
        WorkspaceLocationRef locationRef = new WorkspaceLocationRef("location-1");
        locations.register(locationRef, root);
        WorkspaceBinding binding = WorkspaceBinding.provision(
                        bindingId,
                        locationRef,
                        WorkspaceBindingMode.DIRECT,
                        new PrincipalRef("owner", "user"),
                        WorkspaceCapabilitySet.readWriteFiles(),
                        WorkspacePermissionSet.readWrite(),
                        LocalWorkspaceLocationStore.fingerprintFor(root),
                        NOW)
                .activate(NOW);
        bindings.create(binding);
        Workspace workspace = Workspace.provision(
                        workspaceId,
                        new ProjectId("project-1"),
                        WorkspacePurpose.PRIMARY,
                        new WorkspaceRoot(ProjectPath.root(), bindingId, "test"),
                        WorkspaceRevision.initial(binding.rootFingerprint()),
                        NOW)
                .activate(NOW);
        workspaces.create(workspace);
        var fileService =
                new LocalWorkspaceFileService(workspaces, bindings, locations, SensitivePathPolicy.defaults());
        return new Fixture(workspace, fileService, new ProjectIndexService(workspaces, fileService, () -> NOW));
    }

    private record Fixture(Workspace workspace, LocalWorkspaceFileService files, ProjectIndexService index) {}
}
