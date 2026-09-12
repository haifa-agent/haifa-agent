package io.haifa.agent.cli;

import io.haifa.agent.application.project.workspace.InMemoryWorkspaceAccessStore;
import io.haifa.agent.application.project.workspace.WorkspaceAccess;
import io.haifa.agent.application.project.workspace.WorkspaceAccessMode;
import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.project.binding.WorkspaceBinding;
import io.haifa.agent.project.binding.WorkspaceBindingId;
import io.haifa.agent.project.binding.WorkspaceBindingMode;
import io.haifa.agent.project.binding.WorkspaceLocationRef;
import io.haifa.agent.project.configuration.ProjectConfigurationId;
import io.haifa.agent.project.core.ledger.InMemorySessionChangeLedger;
import io.haifa.agent.project.core.mutation.InMemoryWorkspaceWriteLeaseManager;
import io.haifa.agent.project.core.store.InMemoryProjectStore;
import io.haifa.agent.project.core.store.InMemoryWorkspaceBindingStore;
import io.haifa.agent.project.core.store.InMemoryWorkspaceStore;
import io.haifa.agent.project.core.workspace.WorkspaceService;
import io.haifa.agent.project.domain.Project;
import io.haifa.agent.project.domain.ProjectConfigurationRef;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.hostworkspace.HostWorkspaceFileService;
import io.haifa.agent.project.hostworkspace.HostWorkspaceLocationStore;
import io.haifa.agent.project.hostworkspace.HostWorkspaceMutationService;
import io.haifa.agent.project.hostworkspace.SensitivePathPolicy;
import io.haifa.agent.project.hostworkspace.scope.AuthorizedHostDirectory;
import io.haifa.agent.project.hostworkspace.scope.AuthorizedWorkspaceProvisioning;
import io.haifa.agent.project.hostworkspace.scope.HostWorkspaceScope;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspaceCapabilitySet;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.project.workspace.WorkspacePermissionSet;
import io.haifa.agent.project.workspace.WorkspacePurpose;
import io.haifa.agent.project.workspace.WorkspaceRevision;
import io.haifa.agent.project.workspace.WorkspaceRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
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
            InMemoryWorkspaceBindingStore bindings,
            HostWorkspaceLocationStore locations,
            AuthorizedWorkspaceProvisioning provisioning,
            InMemorySessionChangeLedger ledger,
            InMemoryWorkspaceAccessStore workspaceAccess,
            LocalFileToolOperations operations,
            PrincipalRef owner,
            TenantRef tenant,
            Instant now) {}

    record SingleRootFixture(
            WorkspaceId workspaceId,
            ProjectId projectId,
            AuthorizedWorkspaceProvisioning provisioning,
            InMemorySessionChangeLedger ledger,
            InMemoryWorkspaceAccessStore workspaceAccess,
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
        WorkspaceId docsWorkspaceId = new WorkspaceId("ws-docs");
        WorkspaceId configWorkspaceId = new WorkspaceId("ws-config");

        var bindings = new InMemoryWorkspaceBindingStore();
        var workspaces = new InMemoryWorkspaceStore();
        var locations = new HostWorkspaceLocationStore();
        ProjectId projectId = new ProjectId("proj-multiroot");
        PrincipalRef owner = new PrincipalRef("owner", "user");
        TenantRef tenant = new TenantRef("local");

        registerWorkspace(locations, bindings, workspaces, workspaceId, workspaceDir, WorkspacePurpose.PRIMARY, now);
        registerWorkspace(locations, bindings, workspaces, docsWorkspaceId, docsDir, WorkspacePurpose.DIRECTORY, now);
        registerWorkspace(
                locations, bindings, workspaces, configWorkspaceId, configDir, WorkspacePurpose.DIRECTORY, now);

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

        var idSequence = new AtomicInteger();
        IdentifierGenerator identifiers = () -> "multi-root-test-" + idSequence.incrementAndGet();
        var workspaceService = new WorkspaceService(projects, workspaces, bindings, identifiers, () -> now);
        var scope = new HostWorkspaceScope(
                List.of(
                        AuthorizedHostDirectory.of(workspaceId, workspaceDir.toRealPath()),
                        AuthorizedHostDirectory.of(docsWorkspaceId, docsDir.toRealPath()),
                        AuthorizedHostDirectory.of(configWorkspaceId, configDir.toRealPath())),
                1L);
        var provisioning = new AuthorizedWorkspaceProvisioning(
                projectId, workspaces, bindings, locations, workspaceService, owner, () -> now, scope);

        var files = new HostWorkspaceFileService(workspaces, bindings, locations, SensitivePathPolicy.defaults());
        var mutations = new HostWorkspaceMutationService(
                workspaces,
                bindings,
                locations,
                SensitivePathPolicy.defaults(),
                new InMemoryWorkspaceWriteLeaseManager(),
                identifiers,
                () -> now);
        var ledger = new InMemorySessionChangeLedger();
        var workspaceAccess = new InMemoryWorkspaceAccessStore();
        workspaceAccess.replace(new WorkspaceAccess(tenant, owner, workspaceId, WorkspaceAccessMode.DEVELOP));
        workspaceAccess.replace(new WorkspaceAccess(tenant, owner, docsWorkspaceId, WorkspaceAccessMode.READ));
        workspaceAccess.replace(new WorkspaceAccess(tenant, owner, configWorkspaceId, WorkspaceAccessMode.DEVELOP));

        var operations = new LocalFileToolOperations(
                workspaces,
                files,
                mutations,
                identifiers,
                () -> now,
                provisioning,
                ledger,
                true,
                workspaceAccess,
                tenant,
                owner);

        return new MultiRootFixture(
                realTempDir,
                workspaceDir,
                docsDir,
                configDir,
                workspaceId,
                docsWorkspaceId,
                configWorkspaceId,
                workspaces,
                bindings,
                locations,
                provisioning,
                ledger,
                workspaceAccess,
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
        WorkspaceBindingId bindingId = new WorkspaceBindingId("binding-file-read");
        WorkspaceLocationRef locationRef = new WorkspaceLocationRef("location-file-read");
        var bindings = new InMemoryWorkspaceBindingStore();
        var workspaces = new InMemoryWorkspaceStore();
        var locations = new HostWorkspaceLocationStore();
        Path realRoot;
        try {
            realRoot = root.toRealPath();
        } catch (IOException e) {
            realRoot = root.toAbsolutePath().normalize();
        }
        locations.register(locationRef, realRoot);
        WorkspaceBinding binding = WorkspaceBinding.provision(
                        bindingId,
                        locationRef,
                        WorkspaceBindingMode.DIRECT,
                        new PrincipalRef("owner", "user"),
                        WorkspaceCapabilitySet.readWriteFiles(),
                        WorkspacePermissionSet.readWrite(),
                        HostWorkspaceLocationStore.fingerprintFor(realRoot),
                        now)
                .activate(now);
        bindings.create(binding);
        workspaces.create(Workspace.provision(
                        workspaceId,
                        projectId,
                        WorkspacePurpose.PRIMARY,
                        new WorkspaceRoot(ProjectPath.root(), bindingId, "test"),
                        WorkspaceRevision.initial(binding.rootFingerprint()),
                        now)
                .activate(now));
        var files = new HostWorkspaceFileService(workspaces, bindings, locations, SensitivePathPolicy.defaults());
        HostWorkspaceScope scope = HostWorkspaceScope.initial(AuthorizedHostDirectory.of(workspaceId, realRoot));
        var sequence = new AtomicInteger();
        IdentifierGenerator identifiers = () -> "file-tool-test-" + sequence.incrementAndGet();
        PrincipalRef owner = new PrincipalRef("owner", "user");
        var projects = new InMemoryProjectStore();
        projects.create(Project.create(
                        projectId,
                        new TenantRef("local"),
                        owner,
                        "file-tool-test",
                        "test project",
                        new ProjectConfigurationRef(new ProjectConfigurationId("config-1").value(), "1.0.0"),
                        now,
                        Map.of())
                .assignDefaultWorkspace(workspaceId, now));
        var workspaceService = new WorkspaceService(projects, workspaces, bindings, identifiers, () -> now);
        var provisioning = new AuthorizedWorkspaceProvisioning(
                projectId, workspaces, bindings, locations, workspaceService, owner, () -> now, scope);
        var mutations = new HostWorkspaceMutationService(
                workspaces,
                bindings,
                locations,
                SensitivePathPolicy.defaults(),
                new InMemoryWorkspaceWriteLeaseManager(),
                identifiers,
                () -> now);
        TenantRef tenant = new TenantRef("local");
        var workspaceAccess = new InMemoryWorkspaceAccessStore();
        workspaceAccess.replace(new WorkspaceAccess(tenant, owner, workspaceId, WorkspaceAccessMode.DEVELOP));
        var operations = new LocalFileToolOperations(
                workspaces,
                files,
                mutations,
                identifiers,
                () -> now,
                provisioning,
                ledger,
                workspaceAttachmentDisclosed,
                workspaceAccess,
                tenant,
                owner);
        return new SingleRootFixture(workspaceId, projectId, provisioning, ledger, workspaceAccess, operations);
    }

    private static void registerWorkspace(
            HostWorkspaceLocationStore locations,
            InMemoryWorkspaceBindingStore bindings,
            InMemoryWorkspaceStore workspaces,
            WorkspaceId id,
            Path directory,
            WorkspacePurpose purpose,
            Instant now)
            throws IOException {
        WorkspaceLocationRef locationRef = new WorkspaceLocationRef("loc-" + id.value());
        WorkspaceBindingId bindingId = new WorkspaceBindingId("binding-" + id.value());
        Path realPath = directory.toRealPath();
        locations.register(locationRef, realPath);
        WorkspaceBinding binding = WorkspaceBinding.provision(
                        bindingId,
                        locationRef,
                        WorkspaceBindingMode.DIRECT,
                        new PrincipalRef("owner", "user"),
                        WorkspaceCapabilitySet.readWriteFiles(),
                        WorkspacePermissionSet.readWrite(),
                        HostWorkspaceLocationStore.fingerprintFor(realPath),
                        now)
                .activate(now);
        bindings.create(binding);
        workspaces.create(Workspace.provision(
                        id,
                        new ProjectId("proj-multiroot"),
                        purpose,
                        new WorkspaceRoot(ProjectPath.root(), bindingId, "test"),
                        WorkspaceRevision.initial(binding.rootFingerprint()),
                        now)
                .activate(now));
    }
}
