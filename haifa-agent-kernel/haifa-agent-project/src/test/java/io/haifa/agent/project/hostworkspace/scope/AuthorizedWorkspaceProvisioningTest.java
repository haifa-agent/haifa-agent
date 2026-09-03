package io.haifa.agent.project.hostworkspace.scope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.project.binding.WorkspaceBinding;
import io.haifa.agent.project.binding.WorkspaceBindingId;
import io.haifa.agent.project.binding.WorkspaceBindingMode;
import io.haifa.agent.project.binding.WorkspaceLocationRef;
import io.haifa.agent.project.configuration.ProjectConfigurationId;
import io.haifa.agent.project.domain.Project;
import io.haifa.agent.project.domain.ProjectConfigurationRef;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.hostworkspace.HostWorkspaceLocationStore;
import io.haifa.agent.project.hostworkspace.scope.AuthorizedWorkspaceProvisioning.ProvisioningResult;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.store.InMemoryProjectStore;
import io.haifa.agent.project.store.InMemoryWorkspaceBindingStore;
import io.haifa.agent.project.store.InMemoryWorkspaceStore;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.project.workspace.WorkspacePurpose;
import io.haifa.agent.project.workspace.WorkspaceRevision;
import io.haifa.agent.project.workspace.WorkspaceRoot;
import io.haifa.agent.project.workspace.WorkspaceService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuthorizedWorkspaceProvisioningTest {
    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");

    @TempDir
    Path tempDir;

    private Path initialRoot;
    private Path additionalRoot;
    private Path outsideRoot;
    private ProjectId projectId;
    private HostWorkspaceLocationStore locations;
    private InMemoryWorkspaceStore workspaceStore;
    private AuthorizedWorkspaceProvisioning provisioning;

    @BeforeEach
    void setUp() throws IOException {
        // Keep authorized roots and later tool inputs on one canonical host-path representation.
        tempDir = tempDir.toRealPath();
        initialRoot = Files.createDirectories(tempDir.resolve("initial"));
        additionalRoot = Files.createDirectories(tempDir.resolve("additional"));
        outsideRoot = Files.createDirectories(tempDir.resolve("outside"));

        projectId = new ProjectId("local-project-v1-test");
        var projectStore = new InMemoryProjectStore();
        workspaceStore = new InMemoryWorkspaceStore();
        var bindingStore = new InMemoryWorkspaceBindingStore();
        locations = new HostWorkspaceLocationStore();

        TimeProvider time = () -> NOW;
        var owner = new PrincipalRef("owner", "user");
        WorkspaceLocationRef initialLocationRef = new WorkspaceLocationRef("local-location-v1:initial");
        locations.register(initialLocationRef, initialRoot.toRealPath());
        WorkspaceBinding initialBinding = WorkspaceBinding.provision(
                        new WorkspaceBindingId("local-binding-v1-initial"),
                        initialLocationRef,
                        WorkspaceBindingMode.DIRECT,
                        owner,
                        io.haifa.agent.project.workspace.WorkspaceCapabilitySet.readWriteFiles(),
                        io.haifa.agent.project.workspace.WorkspacePermissionSet.readWrite(),
                        HostWorkspaceLocationStore.fingerprintFor(initialRoot.toRealPath()),
                        NOW)
                .activate(NOW);
        bindingStore.create(initialBinding);
        Workspace initialWorkspace = Workspace.provision(
                        new WorkspaceId("local-workspace-v1-initial"),
                        projectId,
                        WorkspacePurpose.PRIMARY,
                        new WorkspaceRoot(ProjectPath.root(), initialBinding.id(), "local-guarded"),
                        WorkspaceRevision.initial(initialBinding.rootFingerprint()),
                        NOW)
                .activate(NOW);
        workspaceStore.create(initialWorkspace);
        Project project = Project.create(
                        projectId,
                        new TenantRef("local"),
                        owner,
                        "workspace-test",
                        "test project",
                        new ProjectConfigurationRef(new ProjectConfigurationId("config-1").value(), "1.0.0"),
                        NOW,
                        java.util.Map.of())
                .assignDefaultWorkspace(initialWorkspace.id(), NOW);
        projectStore.create(project);

        WorkspaceService workspaceService =
                new WorkspaceService(projectStore, workspaceStore, bindingStore, () -> "generated-id", time);
        HostWorkspaceScope initialScope = HostWorkspaceScope.initial(AuthorizedHostDirectory.of(
                initialWorkspace.id(), initialRoot.toRealPath(), HostDirectoryPermission.READ_WRITE));
        provisioning = new AuthorizedWorkspaceProvisioning(
                projectId, workspaceStore, bindingStore, locations, workspaceService, owner, time, initialScope);
    }

    @Test
    void authorizesApprovedDirectoryAsPeerWorkspace() throws IOException {
        ProvisioningResult result = provisioning.authorize(additionalRoot, HostDirectoryPermission.READ_WRITE);

        assertThat(result.reusedExistingBoundary()).isFalse();
        assertThat(result.directory().permission()).isEqualTo(HostDirectoryPermission.READ_WRITE);
        assertThat(result.directory().realPath()).isEqualTo(additionalRoot.toRealPath());
        assertThat(result.directory().workspaceId().value()).startsWith("local-directory-ws-v1-");
        assertThat(provisioning.scope().version()).isEqualTo(2L);
        HostDirectoryIdentity identity = HostDirectoryIdentity.resolve(additionalRoot.toRealPath());
        assertThat(result.directory().workspaceId()).isEqualTo(identity.workspaceId());
        assertThat(locations.contains(identity.locationRef())).isTrue();
    }

    @Test
    void resolvesAuthorizedDirectoryAfterApproval() throws IOException {
        Files.writeString(additionalRoot.resolve("doc.md"), "content", StandardCharsets.UTF_8);
        provisioning.authorize(additionalRoot, HostDirectoryPermission.READ_WRITE);

        ResolvedAuthorizedPath resolved =
                provisioning.scope().resolve(additionalRoot.resolve("doc.md").toString());

        assertThat(resolved.directory().realPath()).isEqualTo(additionalRoot.toRealPath());
        assertThat(resolved.workspacePath().projectPath()).isEqualTo(ProjectPath.of("doc.md"));
    }

    @Test
    void recoversSameWorkspaceIdWhenReauthorizedAfterRevocation() {
        ProvisioningResult first = provisioning.authorize(additionalRoot, HostDirectoryPermission.READ_WRITE);
        provisioning.revoke(first.directory().workspaceId());
        assertThat(provisioning.scope().allowedDirectories()).hasSize(1);
        assertThatCode(() -> provisioning.scope().resolve(additionalRoot.toString()))
                .isInstanceOf(HostWorkspaceScopeException.class);

        ProvisioningResult second = provisioning.authorize(additionalRoot, HostDirectoryPermission.READ_WRITE);

        assertThat(second.directory().workspaceId()).isEqualTo(first.directory().workspaceId());
        assertThat(second.recovered()).isTrue();
        assertThat(provisioning.scope().allowedDirectories()).hasSize(2);
    }

    @Test
    void rejectsPermissionChangesWhenReauthorizingARecoveredDirectory() {
        ProvisioningResult readOnly = provisioning.authorize(additionalRoot, HostDirectoryPermission.READ_ONLY);
        provisioning.revoke(readOnly.directory().workspaceId());

        assertThatThrownBy(() -> provisioning.authorize(additionalRoot, HostDirectoryPermission.READ_WRITE))
                .isInstanceOfSatisfying(HostWorkspaceScopeException.class, exception -> assertThat(exception.code())
                        .isEqualTo(HostWorkspaceScopeErrorCode.PERMISSION_DENIED));

        ProvisioningResult readWrite = provisioning.authorize(outsideRoot, HostDirectoryPermission.READ_WRITE);
        provisioning.revoke(readWrite.directory().workspaceId());

        assertThatThrownBy(() -> provisioning.authorize(outsideRoot, HostDirectoryPermission.READ_ONLY))
                .isInstanceOfSatisfying(HostWorkspaceScopeException.class, exception -> assertThat(exception.code())
                        .isEqualTo(HostWorkspaceScopeErrorCode.PERMISSION_DENIED));
        assertThat(provisioning.scope().allowedDirectories()).hasSize(1);
    }

    @Test
    void reuseExistingBoundaryWhenCoveredByAuthorizedParent() throws IOException {
        provisioning.authorize(additionalRoot, HostDirectoryPermission.READ_WRITE);
        Path nested = Files.createDirectories(additionalRoot.resolve("nested"));

        ProvisioningResult result = provisioning.authorize(nested, HostDirectoryPermission.READ_WRITE);

        assertThat(result.reusedExistingBoundary()).isTrue();
        assertThat(result.directory().realPath()).isEqualTo(additionalRoot.toRealPath());
        assertThat(provisioning.scope().allowedDirectories()).hasSize(2);
        assertThat(provisioning.scope().version()).isEqualTo(2L);
    }

    @Test
    void cannotElevateCoveredDirectoryBeyondEnclosingReadOnlyBoundary() throws IOException {
        Path nested = Files.createDirectories(additionalRoot.resolve("nested"));
        provisioning.authorize(additionalRoot, HostDirectoryPermission.READ_ONLY);

        assertThatThrownBy(() -> provisioning.authorize(nested, HostDirectoryPermission.READ_WRITE))
                .isInstanceOfSatisfying(HostWorkspaceScopeException.class, exception -> assertThat(exception.code())
                        .isEqualTo(HostWorkspaceScopeErrorCode.PERMISSION_DENIED));
    }

    @Test
    void rejectsDirectoryThatWouldSwallowExistingBoundary() throws IOException {
        Path parent = Files.createDirectories(tempDir.resolve("swallow-parent"));
        Path child = Files.createDirectories(parent.resolve("swallow-child"));
        provisioning.authorize(child, HostDirectoryPermission.READ_WRITE);

        assertThatThrownBy(() -> provisioning.authorize(parent, HostDirectoryPermission.READ_WRITE))
                .isInstanceOfSatisfying(HostWorkspaceScopeException.class, exception -> assertThat(exception.code())
                        .isEqualTo(HostWorkspaceScopeErrorCode.INVALID_ARGUMENT));
    }

    @Test
    void rejectsSymbolicLinkDirectory() throws IOException {
        Path target = Files.createDirectories(tempDir.resolve("link-target"));
        Path link;
        try {
            link = Files.createSymbolicLink(tempDir.resolve("link-dir"), target);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException exception) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symbolic links are unavailable on this test host");
            return;
        }

        assertThatThrownBy(() -> provisioning.authorize(link, HostDirectoryPermission.READ_WRITE))
                .isInstanceOf(HostWorkspaceScopeException.class);
    }

    @Test
    void rejectsMissingDirectory() {
        Path missing = tempDir.resolve("does-not-exist");

        assertThatThrownBy(() -> provisioning.authorize(missing, HostDirectoryPermission.READ_WRITE))
                .isInstanceOfSatisfying(HostWorkspaceScopeException.class, exception -> assertThat(exception.code())
                        .isEqualTo(HostWorkspaceScopeErrorCode.INVALID_ARGUMENT));
    }

    @Test
    void revocationImmediatelyFailsResolutionAndKeepsLogicalFacts() throws IOException {
        Files.writeString(additionalRoot.resolve("file.txt"), "data", StandardCharsets.UTF_8);
        ProvisioningResult result = provisioning.authorize(additionalRoot, HostDirectoryPermission.READ_WRITE);
        WorkspaceId workspaceId = result.directory().workspaceId();
        HostWorkspaceScope snapshot = provisioning.scope();
        ResolvedAuthorizedPath resolved =
                snapshot.resolve(additionalRoot.resolve("file.txt").toString());

        provisioning.revoke(workspaceId);

        assertThat(Files.exists(additionalRoot.resolve("file.txt"))).isTrue();
        assertThatThrownBy(() -> provisioning.scope().resolve(resolved.absoluteInput()))
                .isInstanceOfSatisfying(HostWorkspaceScopeException.class, exception -> assertThat(exception.code())
                        .isEqualTo(HostWorkspaceScopeErrorCode.ACCESS_DENIED));
        assertThatThrownBy(() -> provisioning.requireUnchanged(snapshot))
                .isInstanceOfSatisfying(HostWorkspaceScopeException.class, exception -> assertThat(exception.code())
                        .isEqualTo(HostWorkspaceScopeErrorCode.ACCESS_DENIED));
    }

    @Test
    void requireUnchangedDetectsConcurrentRevocationBeforeWriteIo() {
        ProvisioningResult result = provisioning.authorize(additionalRoot, HostDirectoryPermission.READ_WRITE);
        HostWorkspaceScope snapshot = provisioning.scope();

        provisioning.revoke(result.directory().workspaceId());

        assertThatThrownBy(() -> provisioning.requireUnchanged(snapshot))
                .isInstanceOfSatisfying(HostWorkspaceScopeException.class, exception -> assertThat(exception.code())
                        .isEqualTo(HostWorkspaceScopeErrorCode.ACCESS_DENIED));
    }

    @Test
    void revokeOfUnknownWorkspaceIsRejected() {
        assertThatThrownBy(() -> provisioning.revoke(new WorkspaceId("ws-unknown")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void workspaceProvisionedWithDirectoryPurpose() throws IOException {
        provisioning.authorize(additionalRoot, HostDirectoryPermission.READ_WRITE);
        Workspace workspace = workspaceStore
                .find(HostDirectoryIdentity.resolve(additionalRoot.toRealPath()).workspaceId())
                .orElseThrow();

        assertThat(workspace.purpose()).isEqualTo(WorkspacePurpose.DIRECTORY);
    }
}
