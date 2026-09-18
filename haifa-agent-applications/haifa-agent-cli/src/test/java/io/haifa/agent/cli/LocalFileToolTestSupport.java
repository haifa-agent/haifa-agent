package io.haifa.agent.cli;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.project.configuration.ProjectConfigurationId;
import io.haifa.agent.project.core.ledger.InMemorySessionChangeLedger;
import io.haifa.agent.project.core.mutation.InMemoryWorkspaceWriteLeaseManager;
import io.haifa.agent.project.core.store.InMemoryProjectStore;
import io.haifa.agent.project.core.store.InMemoryWorkspaceStore;
import io.haifa.agent.project.core.workspace.WorkspaceService;
import io.haifa.agent.project.domain.Project;
import io.haifa.agent.project.domain.ProjectConfigurationRef;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.hostworkspace.HostWorkspaceFileService;
import io.haifa.agent.project.hostworkspace.HostWorkspaceLocationStore;
import io.haifa.agent.project.hostworkspace.HostWorkspaceMutationService;
import io.haifa.agent.project.hostworkspace.SensitivePathPolicy;
import io.haifa.agent.project.hostworkspace.directory.InMemoryAuthorizedDirectoryStore;
import io.haifa.agent.project.hostworkspace.scope.AuthorizedHostDirectory;
import io.haifa.agent.project.hostworkspace.scope.AuthorizedWorkspaceProvisioning;
import io.haifa.agent.project.hostworkspace.scope.HostWorkspaceScope;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspaceAccessMode;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.project.workspace.WorkspaceRevision;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

final class LocalFileToolTestSupport {

    private LocalFileToolTestSupport() {}

    record MultiRootFixture(
            Path tempDir,
            Path workspaceDir,
            Path docsDir,
            Path configDir,
            WorkspaceId workspaceId,
            WorkspaceId docsWorkspaceId,
            WorkspaceId configWorkspaceId,
            InMemoryWorkspaceStore workspaces,
            HostWorkspaceLocationStore locations,
            AuthorizedWorkspaceProvisioning provisioning,
            InMemorySessionChangeLedger ledger,
            InMemoryAuthorizedDirectoryStore registry,
            ProjectId projectId,
            LocalFileToolOperations operations,
            PrincipalRef owner,
            TenantRef tenant,
            Instant now) {}

    record SingleRootFixture(
            WorkspaceId workspaceId,
            ProjectId projectId,
            AuthorizedWorkspaceProvisioning provisioning,
            InMemorySessionChangeLedger ledger,
            LocalFileToolOperations operations) {}

    static MultiRootFixture createMultiRootFixture(Path tempDir) throws IOException {
        Path realTempDir = tempDir.toRealPath();
        Path workspaceDir = realTempDir.resolve("workspace-repo");
        Path docsDir = realTempDir.resolve("docs-repo");
        Path configDir = realTempDir.resolve("config-repo");
        Files.createDirectories(workspaceDir);
        Files.createDirectories(docsDir);
        Files.createDirectories(configDir);

        Instant now = Instant.parse("2026-08-05T00:00:00Z");
        WorkspaceId workspaceId = new WorkspaceId("ws-multiroot");

        var workspaces = new InMemoryWorkspaceStore();
        var locations = new HostWorkspaceLocationStore();
        var registry = new InMemoryAuthorizedDirectoryStore();
        ProjectId projectId = new ProjectId("proj-multiroot");
        PrincipalRef owner = new PrincipalRef("owner", "user");
        TenantRef tenant = new TenantRef("local");

        var projects = new InMemoryProjectStore();
        projects.create(Project.create(
                        projectId,
                        tenant,
                        owner,
                        "multi-root-test",
                        "test project",
                        new ProjectConfigurationRef(new ProjectConfigurationId("config-1").value(), "1.0.0"),
                        now,
                        Map.of())
                .assignDefaultWorkspace(workspaceId, now));
        workspaces.create(Workspace.provision(workspaceId, projectId, WorkspaceRevision.initial("multi-root"), now)
                .activate(now));
        locations.register(workspaceId, workspaceDir.toRealPath());

        var idSequence = new AtomicInteger();
        IdentifierGenerator identifiers = () -> "multi-root-test-" + idSequence.incrementAndGet();
        var workspaceService = new WorkspaceService(projects, workspaces, () -> now);
        HostWorkspaceScope scope =
                HostWorkspaceScope.initial(AuthorizedHostDirectory.of(workspaceId, workspaceDir.toRealPath()));
        var provisioning = new AuthorizedWorkspaceProvisioning(
                projectId,
                workspaces,
                locations,
                workspaceService,
                tenant,
                owner,
                () -> now,
                scope,
                registry,
                "workspace-repo");
        WorkspaceId docsWorkspaceId = provisioning
                .authorizeApprovedAttach(docsDir, WorkspaceAccessMode.READ)
                .directory()
                .workspaceId();
        WorkspaceId configWorkspaceId = provisioning
                .authorizeApprovedAttach(configDir, WorkspaceAccessMode.DEVELOP)
                .directory()
                .workspaceId();

        var files = new HostWorkspaceFileService(workspaces, locations, SensitivePathPolicy.defaults());
        var mutations = new HostWorkspaceMutationService(
                workspaces,
                locations,
                SensitivePathPolicy.defaults(),
                new InMemoryWorkspaceWriteLeaseManager(),
                identifiers,
                () -> now);
        var ledger = new InMemorySessionChangeLedger();

        var operations = new LocalFileToolOperations(
                workspaces, files, mutations, identifiers, () -> now, provisioning, ledger, true, tenant, owner);

        return new MultiRootFixture(
                realTempDir,
                workspaceDir,
                docsDir,
                configDir,
                workspaceId,
                docsWorkspaceId,
                configWorkspaceId,
                workspaces,
                locations,
                provisioning,
                ledger,
                registry,
                projectId,
                operations,
                owner,
                tenant,
                now);
    }

    static SingleRootFixture createSingleRootFixture(Path root) {
        return createSingleRootFixture(root, null, false);
    }

    static SingleRootFixture createSingleRootFixture(Path root, InMemorySessionChangeLedger ledger) {
        return createSingleRootFixture(root, ledger, false);
    }

    static SingleRootFixture createSingleRootFixture(Path root, boolean workspaceAttachmentDisclosed) {
        return createSingleRootFixture(root, null, workspaceAttachmentDisclosed);
    }

    static SingleRootFixture createSingleRootFixture(
            Path root, InMemorySessionChangeLedger ledger, boolean workspaceAttachmentDisclosed) {
        Instant now = Instant.parse("2026-08-05T00:00:00Z");
        WorkspaceId workspaceId = new WorkspaceId("workspace-file-read");
        ProjectId projectId = new ProjectId("project-file-read");
        var workspaces = new InMemoryWorkspaceStore();
        var locations = new HostWorkspaceLocationStore();
        var registry = new InMemoryAuthorizedDirectoryStore();
        Path realRoot;
        try {
            realRoot = root.toRealPath();
        } catch (IOException e) {
            realRoot = root.toAbsolutePath().normalize();
        }
        locations.register(workspaceId, realRoot);
        workspaces.create(Workspace.provision(workspaceId, projectId, WorkspaceRevision.initial("file-read"), now)
                .activate(now));
        var files = new HostWorkspaceFileService(workspaces, locations, SensitivePathPolicy.defaults());
        HostWorkspaceScope scope = HostWorkspaceScope.initial(AuthorizedHostDirectory.of(workspaceId, realRoot));
        var sequence = new AtomicInteger();
        IdentifierGenerator identifiers = () -> "file-tool-test-" + sequence.incrementAndGet();
        PrincipalRef owner = new PrincipalRef("owner", "user");
        TenantRef tenant = new TenantRef("local");
        var projects = new InMemoryProjectStore();
        projects.create(Project.create(
                        projectId,
                        tenant,
                        owner,
                        "file-tool-test",
                        "test project",
                        new ProjectConfigurationRef(new ProjectConfigurationId("config-1").value(), "1.0.0"),
                        now,
                        Map.of())
                .assignDefaultWorkspace(workspaceId, now));
        var workspaceService = new WorkspaceService(projects, workspaces, () -> now);
        var provisioning = new AuthorizedWorkspaceProvisioning(
                projectId,
                workspaces,
                locations,
                workspaceService,
                tenant,
                owner,
                () -> now,
                scope,
                registry,
                "workspace-file-read");
        var mutations = new HostWorkspaceMutationService(
                workspaces,
                locations,
                SensitivePathPolicy.defaults(),
                new InMemoryWorkspaceWriteLeaseManager(),
                identifiers,
                () -> now);
        var operations = new LocalFileToolOperations(
                workspaces,
                files,
                mutations,
                identifiers,
                () -> now,
                provisioning,
                ledger,
                workspaceAttachmentDisclosed,
                tenant,
                owner);
        return new SingleRootFixture(workspaceId, projectId, provisioning, ledger, operations);
    }
}
