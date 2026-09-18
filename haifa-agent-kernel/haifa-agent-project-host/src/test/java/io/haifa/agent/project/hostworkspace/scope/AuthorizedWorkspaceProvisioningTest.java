package io.haifa.agent.project.hostworkspace.scope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.project.configuration.ProjectConfigurationId;
import io.haifa.agent.project.core.store.InMemoryProjectStore;
import io.haifa.agent.project.core.store.InMemoryWorkspaceStore;
import io.haifa.agent.project.core.workspace.WorkspaceService;
import io.haifa.agent.project.domain.Project;
import io.haifa.agent.project.domain.ProjectConfigurationRef;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.hostworkspace.HostWorkspaceLocationStore;
import io.haifa.agent.project.hostworkspace.directory.AuthorizedDirectoryEntry;
import io.haifa.agent.project.hostworkspace.directory.AuthorizedDirectoryStatus;
import io.haifa.agent.project.hostworkspace.directory.AuthorizedDirectoryView;
import io.haifa.agent.project.hostworkspace.directory.InMemoryAuthorizedDirectoryStore;
import io.haifa.agent.project.hostworkspace.scope.AuthorizedWorkspaceProvisioning.ProvisioningResult;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspaceAccessMode;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.project.workspace.WorkspaceRevision;
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
    private static final WorkspaceId INITIAL_WORKSPACE = new WorkspaceId("local-workspace-v1-initial");

    @TempDir
    Path tempDir;

    private Path initialRoot;
    private Path additionalRoot;
    private Path outsideRoot;
    private ProjectId projectId;
    private InMemoryProjectStore projectStore;
    private HostWorkspaceLocationStore locations;
    private InMemoryWorkspaceStore workspaceStore;
    private WorkspaceService workspaceService;
    private TenantRef tenant;
    private PrincipalRef owner;
    private TimeProvider time;
    private HostWorkspaceScope initialScope;
    private InMemoryAuthorizedDirectoryStore registry;
    private AuthorizedWorkspaceProvisioning provisioning;

    @BeforeEach
    void setUp() throws IOException {
        // Keep authorized roots and later tool inputs on one canonical host-path representation.
        tempDir = tempDir.toRealPath();
        initialRoot = Files.createDirectories(tempDir.resolve("initial"));
        additionalRoot = Files.createDirectories(tempDir.resolve("additional"));
        outsideRoot = Files.createDirectories(tempDir.resolve("outside"));

        projectId = new ProjectId("local-project-v1-test");
        projectStore = new InMemoryProjectStore();
        workspaceStore = new InMemoryWorkspaceStore();
        locations = new HostWorkspaceLocationStore();

        time = () -> NOW;
        tenant = new TenantRef("local");
        owner = new PrincipalRef("owner", "user");
        locations.register(INITIAL_WORKSPACE, initialRoot.toRealPath());
        Workspace initialWorkspace = Workspace.provision(
                        INITIAL_WORKSPACE, projectId, WorkspaceRevision.initial("initial"), NOW)
                .activate(NOW);
        workspaceStore.create(initialWorkspace);
        Project project = Project.create(
                        projectId,
                        tenant,
                        owner,
                        "workspace-test",
                        "test project",
                        new ProjectConfigurationRef(new ProjectConfigurationId("config-1").value(), "1.0.0"),
                        NOW,
                        java.util.Map.of())
                .assignDefaultWorkspace(initialWorkspace.id(), NOW);
        projectStore.create(project);

        workspaceService = new WorkspaceService(projectStore, workspaceStore, time);
        initialScope =
                HostWorkspaceScope.initial(AuthorizedHostDirectory.of(initialWorkspace.id(), initialRoot.toRealPath()));
        registry = new InMemoryAuthorizedDirectoryStore();
        provisioning = new AuthorizedWorkspaceProvisioning(
                projectId,
                workspaceStore,
                locations,
                workspaceService,
                tenant,
                owner,
                time,
                initialScope,
                registry,
                "workspace-test");
    }

    @Test
    void authorizesApprovedDirectoryAsPeerWorkspace() throws IOException {
        ProvisioningResult result = provisioning.authorize(additionalRoot);

        assertThat(result.reusedExistingBoundary()).isFalse();
        assertThat(result.directory().realPath()).isEqualTo(additionalRoot.toRealPath());
        assertThat(result.directory().workspaceId().value()).startsWith("local-directory-ws-v1-");
        assertThat(provisioning.scope().version()).isEqualTo(2L);
        HostDirectoryIdentity identity = HostDirectoryIdentity.resolve(additionalRoot.toRealPath());
        assertThat(result.directory().workspaceId()).isEqualTo(identity.workspaceId());
        assertThat(locations.contains(identity.workspaceId())).isTrue();
    }

    @Test
    void resolvesAuthorizedDirectoryAfterApproval() throws IOException {
        Files.writeString(additionalRoot.resolve("doc.md"), "content", StandardCharsets.UTF_8);
        provisioning.authorize(additionalRoot);

        ResolvedAuthorizedPath resolved =
                provisioning.scope().resolve(additionalRoot.resolve("doc.md").toString());

        assertThat(resolved.directory().realPath()).isEqualTo(additionalRoot.toRealPath());
        assertThat(resolved.workspacePath().projectPath()).isEqualTo(ProjectPath.of("doc.md"));
    }

    @Test
    void recoversSameWorkspaceIdWhenReauthorizedAfterRevocation() {
        ProvisioningResult first = provisioning.authorize(additionalRoot);
        provisioning.revoke(first.directory().workspaceId());
        assertThat(provisioning.scope().allowedDirectories()).hasSize(1);
        assertThatCode(() -> provisioning.scope().resolve(additionalRoot.toString()))
                .isInstanceOf(HostWorkspaceScopeException.class);

        ProvisioningResult second = provisioning.authorize(additionalRoot);

        assertThat(second.directory().workspaceId()).isEqualTo(first.directory().workspaceId());
        assertThat(second.recovered()).isTrue();
        assertThat(provisioning.scope().allowedDirectories()).hasSize(2);
    }

    @Test
    void reuseExistingBoundaryWhenCoveredByAuthorizedParent() throws IOException {
        provisioning.authorize(additionalRoot);
        Path nested = Files.createDirectories(additionalRoot.resolve("nested"));

        ProvisioningResult result = provisioning.authorize(nested);

        assertThat(result.reusedExistingBoundary()).isTrue();
        assertThat(result.directory().realPath()).isEqualTo(additionalRoot.toRealPath());
        assertThat(provisioning.scope().allowedDirectories()).hasSize(2);
        assertThat(provisioning.scope().version()).isEqualTo(2L);
    }

    @Test
    void rejectsDirectoryThatWouldSwallowExistingBoundary() throws IOException {
        Path parent = Files.createDirectories(tempDir.resolve("swallow-parent"));
        Path child = Files.createDirectories(parent.resolve("swallow-child"));
        provisioning.authorize(child);

        assertThatThrownBy(() -> provisioning.authorize(parent))
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

        assertThatThrownBy(() -> provisioning.authorize(link)).isInstanceOf(HostWorkspaceScopeException.class);
    }

    @Test
    void rejectsMissingDirectory() {
        Path missing = tempDir.resolve("does-not-exist");

        assertThatThrownBy(() -> provisioning.authorize(missing))
                .isInstanceOfSatisfying(HostWorkspaceScopeException.class, exception -> assertThat(exception.code())
                        .isEqualTo(HostWorkspaceScopeErrorCode.INVALID_ARGUMENT));
    }

    @Test
    void revocationImmediatelyFailsResolutionAndKeepsLogicalFacts() throws IOException {
        Files.writeString(additionalRoot.resolve("file.txt"), "data", StandardCharsets.UTF_8);
        ProvisioningResult result = provisioning.authorize(additionalRoot);
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
        ProvisioningResult result = provisioning.authorize(additionalRoot);
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
    void requireAuthorizedEnforcesOwnerAndModeAndRevocation() {
        ProvisioningResult result = provisioning.authorize(additionalRoot);
        WorkspaceId workspaceId = result.directory().workspaceId();

        assertThat(provisioning
                        .requireAuthorized(tenant, owner, workspaceId, WorkspaceAccessMode.DEVELOP)
                        .mode())
                .isEqualTo(WorkspaceAccessMode.DEVELOP);
        assertThatCode(() -> provisioning.requireAuthorized(tenant, owner, workspaceId, WorkspaceAccessMode.READ))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> provisioning.requireAuthorized(
                        tenant, new PrincipalRef("other", "user"), workspaceId, WorkspaceAccessMode.READ))
                .isInstanceOf(SecurityException.class)
                .hasMessage("WORKSPACE_ACCESS_UNAVAILABLE");

        provisioning.revoke(workspaceId);

        assertThatThrownBy(() -> provisioning.requireAuthorized(tenant, owner, workspaceId, WorkspaceAccessMode.READ))
                .isInstanceOf(SecurityException.class)
                .hasMessage("WORKSPACE_ACCESS_UNAVAILABLE");
    }

    @Test
    void readOnlyAttachmentRefusesDevelopRequirement() {
        ProvisioningResult result = provisioning.authorizeApprovedAttach(additionalRoot, WorkspaceAccessMode.READ);

        assertThatThrownBy(() -> provisioning.requireAuthorized(
                        tenant, owner, result.directory().workspaceId(), WorkspaceAccessMode.DEVELOP))
                .isInstanceOf(SecurityException.class)
                .hasMessage("WORKSPACE_ACCESS_MODE_DENIED");
        assertThatCode(() -> provisioning.requireAuthorized(
                        tenant, owner, result.directory().workspaceId(), WorkspaceAccessMode.READ))
                .doesNotThrowAnyException();
    }

    @Test
    void persistsApprovedAttachAndRestoresItIntoANewScope() throws IOException {
        ProvisioningResult result = provisioning.authorize(additionalRoot);

        assertThat(registry.find(projectId, result.directory().workspaceId()))
                .get()
                .satisfies(entry -> {
                    assertThat(entry.status()).isEqualTo(AuthorizedDirectoryStatus.ACTIVE);
                    assertThat(entry.safeDisplayName()).doesNotContain(additionalRoot.toString());
                });

        var reopenedLocations = new HostWorkspaceLocationStore();
        reopenedLocations.register(INITIAL_WORKSPACE, initialRoot.toRealPath());
        var reopened = new AuthorizedWorkspaceProvisioning(
                projectId,
                workspaceStore,
                reopenedLocations,
                workspaceService,
                tenant,
                owner,
                time,
                initialScope,
                registry,
                "workspace-test");

        assertThat(reopened.scope().allowedDirectories())
                .extracting(AuthorizedHostDirectory::workspaceId)
                .contains(result.directory().workspaceId());
        assertThat(reopened.scope()
                        .resolve(additionalRoot.resolve("restored.txt").toString())
                        .workspacePath()
                        .workspaceId())
                .isEqualTo(result.directory().workspaceId());
        assertThat(reopened.directoryViews())
                .allSatisfy(view -> assertThat(view.toString()).doesNotContain(additionalRoot.toString()));
    }

    @Test
    void missingAttachedDirectoryIsDisabledDuringRecovery() throws IOException {
        ProvisioningResult result = provisioning.authorize(additionalRoot);
        Files.delete(additionalRoot);

        var reopenedLocations = new HostWorkspaceLocationStore();
        reopenedLocations.register(INITIAL_WORKSPACE, initialRoot.toRealPath());
        var reopened = new AuthorizedWorkspaceProvisioning(
                projectId,
                workspaceStore,
                reopenedLocations,
                workspaceService,
                tenant,
                owner,
                time,
                initialScope,
                registry,
                "workspace-test");

        assertThat(reopened.scope().allowedDirectories()).hasSize(1);
        assertThat(registry.find(projectId, result.directory().workspaceId()))
                .get()
                .extracting(entry -> entry.status())
                .isEqualTo(AuthorizedDirectoryStatus.DISABLED);
    }

    @Test
    void unchangedRootRestoresOnRestart() throws IOException {
        ProvisioningResult result = provisioning.authorize(additionalRoot);

        var reopenedLocations = new HostWorkspaceLocationStore();
        reopenedLocations.register(INITIAL_WORKSPACE, initialRoot.toRealPath());
        var reopened = new AuthorizedWorkspaceProvisioning(
                projectId,
                workspaceStore,
                reopenedLocations,
                workspaceService,
                tenant,
                owner,
                time,
                initialScope,
                registry,
                "workspace-test");

        assertThat(reopened.scope().allowedDirectories())
                .extracting(AuthorizedHostDirectory::workspaceId)
                .contains(result.directory().workspaceId());
        assertThat(registry.find(projectId, result.directory().workspaceId()))
                .get()
                .extracting(entry -> entry.status())
                .isEqualTo(AuthorizedDirectoryStatus.ACTIVE);
    }

    @Test
    void physicalReplacementAtTheSamePathIsDisabledOnRestartAndRequiresExplicitReauthorization() throws IOException {
        ProvisioningResult result = provisioning.authorize(additionalRoot);
        WorkspaceId workspaceId = result.directory().workspaceId();
        String approvedFingerprint =
                registry.find(projectId, workspaceId).orElseThrow().physicalFingerprint();

        Path originalDirectory = tempDir.resolve("original-additional");
        Files.move(additionalRoot, originalDirectory);
        Files.createDirectory(additionalRoot);
        HostDirectoryIdentity replacementIdentity = HostDirectoryIdentity.resolve(additionalRoot.toRealPath());
        assertThat(replacementIdentity.workspaceId()).isEqualTo(workspaceId);
        assertThat(replacementIdentity.physicalFingerprint()).isNotEqualTo(approvedFingerprint);

        var reopenedLocations = new HostWorkspaceLocationStore();
        reopenedLocations.register(INITIAL_WORKSPACE, initialRoot.toRealPath());
        var reopened = new AuthorizedWorkspaceProvisioning(
                projectId,
                workspaceStore,
                reopenedLocations,
                workspaceService,
                tenant,
                owner,
                time,
                initialScope,
                registry,
                "workspace-test");

        assertThat(reopened.scope().allowedDirectories())
                .extracting(AuthorizedHostDirectory::workspaceId)
                .doesNotContain(workspaceId);
        assertThat(registry.find(projectId, workspaceId))
                .get()
                .extracting(AuthorizedDirectoryEntry::status)
                .isEqualTo(AuthorizedDirectoryStatus.DISABLED);

        ProvisioningResult reauthorized = reopened.authorize(additionalRoot);

        assertThat(reauthorized.directory().workspaceId()).isEqualTo(workspaceId);
        assertThat(reopened.scope().allowedDirectories())
                .extracting(AuthorizedHostDirectory::workspaceId)
                .contains(workspaceId);
        assertThat(registry.find(projectId, workspaceId)).get().satisfies(entry -> {
            assertThat(entry.status()).isEqualTo(AuthorizedDirectoryStatus.ACTIVE);
            assertThat(entry.physicalFingerprint()).isEqualTo(replacementIdentity.physicalFingerprint());
        });
    }

    @Test
    void revocationIsPersistedAndInitialWorkspaceCannotBeRevoked() {
        ProvisioningResult attached = provisioning.authorize(additionalRoot);

        provisioning.revoke(attached.directory().workspaceId());

        assertThat(registry.find(projectId, attached.directory().workspaceId()))
                .get()
                .extracting(entry -> entry.status())
                .isEqualTo(AuthorizedDirectoryStatus.REVOKED);
        assertThatThrownBy(() -> provisioning.revoke(
                        initialScope.allowedDirectories().getFirst().workspaceId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("initial workspace");

        var reopenedLocations = new HostWorkspaceLocationStore();
        reopenedLocations.register(
                INITIAL_WORKSPACE, initialRoot.toAbsolutePath().normalize());
        var reopened = new AuthorizedWorkspaceProvisioning(
                projectId,
                workspaceStore,
                reopenedLocations,
                workspaceService,
                tenant,
                owner,
                time,
                initialScope,
                registry,
                "workspace-test");
        assertThat(reopened.scope().allowedDirectories()).hasSize(1);
    }

    @Test
    void foreignOwnerActiveRowsAreNotRestoredOrProjected() throws IOException {
        Path foreignRoot = Files.createDirectories(tempDir.resolve("foreign-root"));
        HostDirectoryIdentity foreignIdentity = HostDirectoryIdentity.resolve(foreignRoot.toRealPath());
        registry.create(AuthorizedDirectoryEntry.active(
                projectId,
                foreignIdentity.workspaceId(),
                new TenantRef("foreign"),
                new PrincipalRef("foreign-owner", "user"),
                WorkspaceAccessMode.DEVELOP,
                "foreign-root",
                foreignRoot.toRealPath(),
                foreignIdentity.physicalFingerprint(),
                NOW));

        var reopened = new AuthorizedWorkspaceProvisioning(
                projectId,
                workspaceStore,
                locations,
                workspaceService,
                tenant,
                owner,
                time,
                initialScope,
                registry,
                "workspace-test");

        assertThat(reopened.scope().allowedDirectories())
                .extracting(AuthorizedHostDirectory::workspaceId)
                .doesNotContain(foreignIdentity.workspaceId());
        assertThat(reopened.directoryViews())
                .extracting(AuthorizedDirectoryView::workspaceRef)
                .doesNotContain(foreignIdentity.workspaceId().value());
        assertThat(registry.find(projectId, foreignIdentity.workspaceId()))
                .get()
                .extracting(AuthorizedDirectoryEntry::status)
                .isEqualTo(AuthorizedDirectoryStatus.ACTIVE);
    }

    @Test
    void enclosingReuseRequiresCurrentActiveOwnedEntry() throws IOException {
        Path nested = Files.createDirectories(initialRoot.resolve("nested"));
        AuthorizedDirectoryEntry initial =
                registry.find(projectId, provisioning.initialWorkspaceId()).orElseThrow();
        registry.update(initial.disable("TEST_DISABLE", NOW.plusSeconds(1)), initial.version());

        assertThatThrownBy(() -> provisioning.authorizeApprovedAttach(nested, WorkspaceAccessMode.READ))
                .isInstanceOfSatisfying(HostWorkspaceScopeException.class, exception -> assertThat(exception.code())
                        .isEqualTo(HostWorkspaceScopeErrorCode.ACCESS_DENIED));
    }

    @Test
    void ownedActiveOutOfScopeEntryIsRecoveredViaRevalidation() throws IOException {
        Path target = Files.createDirectories(tempDir.resolve("active-out-of-scope"));
        HostDirectoryIdentity identity = HostDirectoryIdentity.resolve(target.toRealPath());
        registry.create(AuthorizedDirectoryEntry.active(
                projectId,
                identity.workspaceId(),
                tenant,
                owner,
                WorkspaceAccessMode.READ,
                "active-out-of-scope",
                target.toRealPath(),
                identity.physicalFingerprint(),
                NOW));
        assertThat(provisioning.scope().allowedDirectories())
                .extracting(AuthorizedHostDirectory::workspaceId)
                .doesNotContain(identity.workspaceId());

        ProvisioningResult result = provisioning.authorizeApprovedAttach(target, WorkspaceAccessMode.DEVELOP);

        assertThat(result.reusedExistingBoundary()).isFalse();
        assertThat(result.directory().workspaceId()).isEqualTo(identity.workspaceId());
        assertThat(provisioning.scope().allowedDirectories())
                .extracting(AuthorizedHostDirectory::workspaceId)
                .contains(identity.workspaceId());
        assertThat(registry.find(projectId, identity.workspaceId())).get().satisfies(entry -> {
            assertThat(entry.status()).isEqualTo(AuthorizedDirectoryStatus.ACTIVE);
            assertThat(entry.mode()).isEqualTo(WorkspaceAccessMode.READ);
            assertThat(entry.version()).isEqualTo(1L);
        });
    }

    @Test
    void enclosingReuseFailsClosedAfterPhysicalReplacement() throws IOException {
        ProvisioningResult attached = provisioning.authorize(additionalRoot);
        WorkspaceId attachedId = attached.directory().workspaceId();

        Path original = tempDir.resolve("additional-original");
        Files.move(additionalRoot, original);
        Files.createDirectory(additionalRoot);
        Path child = Files.createDirectories(additionalRoot.resolve("child"));

        assertThatThrownBy(() -> provisioning.authorizeApprovedAttach(child, WorkspaceAccessMode.READ))
                .isInstanceOfSatisfying(HostWorkspaceScopeException.class, exception -> assertThat(exception.code())
                        .isEqualTo(HostWorkspaceScopeErrorCode.ACCESS_DENIED));
        assertThat(provisioning.scope().allowedDirectories())
                .extracting(AuthorizedHostDirectory::workspaceId)
                .contains(attachedId);
    }

    @Test
    void unchangedInitialRootRestoresOnRestart() {
        var reopened = new AuthorizedWorkspaceProvisioning(
                projectId,
                workspaceStore,
                locations,
                workspaceService,
                tenant,
                owner,
                time,
                initialScope,
                registry,
                "workspace-test");

        assertThat(reopened.scope().allowedDirectories())
                .extracting(AuthorizedHostDirectory::workspaceId)
                .contains(INITIAL_WORKSPACE);
        assertThat(registry.find(projectId, INITIAL_WORKSPACE))
                .get()
                .extracting(AuthorizedDirectoryEntry::status)
                .isEqualTo(AuthorizedDirectoryStatus.ACTIVE);
    }

    @Test
    void samePathPhysicalReplacementOfInitialRootFailsClosed() throws IOException {
        String originalFingerprint =
                registry.find(projectId, INITIAL_WORKSPACE).orElseThrow().physicalFingerprint();

        Path original = tempDir.resolve("initial-original");
        Files.move(initialRoot, original);
        Files.createDirectory(initialRoot);

        assertThatThrownBy(() -> new AuthorizedWorkspaceProvisioning(
                        projectId,
                        workspaceStore,
                        locations,
                        workspaceService,
                        tenant,
                        owner,
                        time,
                        initialScope,
                        registry,
                        "workspace-test"))
                .isInstanceOfSatisfying(HostWorkspaceScopeException.class, exception -> assertThat(exception.code())
                        .isEqualTo(HostWorkspaceScopeErrorCode.ACCESS_DENIED));
        assertThat(registry.find(projectId, INITIAL_WORKSPACE)).get().satisfies(entry -> {
            assertThat(entry.status()).isEqualTo(AuthorizedDirectoryStatus.ACTIVE);
            assertThat(entry.physicalFingerprint()).isEqualTo(originalFingerprint);
        });
    }
}
