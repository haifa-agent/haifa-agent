package io.haifa.agent.application.project.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.application.project.workspace.WorkspaceAccess;
import io.haifa.agent.application.project.workspace.WorkspaceAccessMode;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.project.binding.WorkspaceBinding;
import io.haifa.agent.project.binding.WorkspaceBindingId;
import io.haifa.agent.project.binding.WorkspaceBindingMode;
import io.haifa.agent.project.binding.WorkspaceLocationRef;
import io.haifa.agent.project.configuration.ProjectConfigurationId;
import io.haifa.agent.project.core.store.InMemoryProjectStore;
import io.haifa.agent.project.core.store.InMemoryWorkspaceBindingStore;
import io.haifa.agent.project.core.store.InMemoryWorkspaceStore;
import io.haifa.agent.project.core.workspace.WorkspaceService;
import io.haifa.agent.project.domain.Project;
import io.haifa.agent.project.domain.ProjectConfigurationRef;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.hostworkspace.HostWorkspaceLocationStore;
import io.haifa.agent.project.hostworkspace.registry.HostWorkspaceRegistryEntry;
import io.haifa.agent.project.hostworkspace.registry.HostWorkspaceRegistryStatus;
import io.haifa.agent.project.hostworkspace.scope.AuthorizedHostDirectory;
import io.haifa.agent.project.hostworkspace.scope.AuthorizedWorkspaceProvisioning;
import io.haifa.agent.project.hostworkspace.scope.HostDirectoryIdentity;
import io.haifa.agent.project.hostworkspace.scope.HostWorkspaceScope;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspaceCapabilitySet;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.project.workspace.WorkspacePermissionSet;
import io.haifa.agent.project.workspace.WorkspacePurpose;
import io.haifa.agent.project.workspace.WorkspaceRevision;
import io.haifa.agent.project.workspace.WorkspaceRoot;
import io.haifa.agent.runtime.core.model.continuation.AesGcmModelContinuationProtector;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceRegistryPathRecoveryIT {
    private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final TenantRef TENANT = new TenantRef("local");
    private static final PrincipalRef OWNER = new PrincipalRef("operator", "user");
    private static final ProjectId PROJECT = new ProjectId("m5-path-recovery");

    @TempDir
    Path directory;

    @Test
    void safeReplacementAtTheSameCanonicalPathKeepsIdentityAndCurrentAccess() throws Exception {
        Path database = directory.resolve("same-path.db");
        Path initial = Files.createDirectory(directory.resolve("same-path-initial"));
        Path attached = Files.createDirectory(directory.resolve("same-path-attached"));
        WorkspaceId workspaceId;
        String originalPhysicalFingerprint;

        try (ProjectPersistenceAssembly first = persistence(database)) {
            Fixture fixture = fixture(initial, first);
            var result = fixture.provisioning.authorizeApprovedAttach(attached, mounted -> first.workspaceAccess()
                    .replace(new WorkspaceAccess(TENANT, OWNER, mounted.workspaceId(), WorkspaceAccessMode.DEVELOP)));
            workspaceId = result.directory().workspaceId();
            originalPhysicalFingerprint = first.workspaceRegistry()
                    .find(PROJECT, workspaceId)
                    .orElseThrow()
                    .fingerprint();
        }

        Files.move(attached, directory.resolve("same-path-original"));
        Files.createDirectory(attached);
        String replacementFingerprint = HostDirectoryIdentity.resolve(attached).physicalFingerprint();
        assertThat(replacementFingerprint).isNotEqualTo(originalPhysicalFingerprint);

        try (ProjectPersistenceAssembly reopened = persistence(database)) {
            Fixture fixture = fixture(initial, reopened);
            assertThat(fixture.provisioning.scope().allowedDirectories())
                    .extracting(AuthorizedHostDirectory::workspaceId)
                    .contains(workspaceId);
            assertThat(reopened.workspaceRegistry().find(PROJECT, workspaceId))
                    .get()
                    .satisfies(entry -> {
                        assertThat(entry.status()).isEqualTo(HostWorkspaceRegistryStatus.ACTIVE);
                        assertThat(entry.fingerprint()).isEqualTo(replacementFingerprint);
                    });
            assertThat(reopened.workspaceAccess().find(TENANT, OWNER, workspaceId))
                    .contains(new WorkspaceAccess(TENANT, OWNER, workspaceId, WorkspaceAccessMode.DEVELOP));
        }
    }

    @Test
    void aDifferentCanonicalPathDoesNotInheritExistingAccess() throws Exception {
        Path database = directory.resolve("different-path.db");
        Path initial = Files.createDirectory(directory.resolve("different-path-initial"));
        Path original = Files.createDirectory(directory.resolve("different-path-original"));
        Path different = Files.createDirectory(directory.resolve("different-path-new"));
        WorkspaceId originalId;

        try (ProjectPersistenceAssembly first = persistence(database)) {
            Fixture fixture = fixture(initial, first);
            originalId = fixture.provisioning
                    .authorizeApprovedAttach(original, ignored -> {})
                    .directory()
                    .workspaceId();
            first.workspaceAccess().replace(new WorkspaceAccess(TENANT, OWNER, originalId, WorkspaceAccessMode.READ));
        }

        try (ProjectPersistenceAssembly reopened = persistence(database)) {
            Fixture fixture = fixture(initial, reopened);
            WorkspaceId differentId = fixture.provisioning
                    .authorizeApprovedAttach(different, ignored -> {})
                    .directory()
                    .workspaceId();
            assertThat(differentId).isNotEqualTo(originalId);
            assertThat(reopened.workspaceAccess().find(TENANT, OWNER, differentId))
                    .isEmpty();
            assertThat(reopened.workspaceAccess().find(TENANT, OWNER, originalId))
                    .contains(new WorkspaceAccess(TENANT, OWNER, originalId, WorkspaceAccessMode.READ));
        }
    }

    @Test
    void revokedWorkspaceDoesNotReactivateAfterRestart() throws Exception {
        Path database = directory.resolve("revoked.db");
        Path initial = Files.createDirectory(directory.resolve("revoked-initial"));
        Path attached = Files.createDirectory(directory.resolve("revoked-attached"));
        WorkspaceId workspaceId;

        try (ProjectPersistenceAssembly first = persistence(database)) {
            Fixture fixture = fixture(initial, first);
            workspaceId = fixture.provisioning
                    .authorizeApprovedAttach(attached, mounted -> first.workspaceAccess()
                            .replace(new WorkspaceAccess(
                                    TENANT, OWNER, mounted.workspaceId(), WorkspaceAccessMode.DEVELOP)))
                    .directory()
                    .workspaceId();
            assertThat(first.workspaceAccess().delete(TENANT, OWNER, workspaceId))
                    .isTrue();
            fixture.provisioning.revoke(workspaceId);
        }

        try (ProjectPersistenceAssembly reopened = persistence(database)) {
            Fixture fixture = fixture(initial, reopened);
            assertThat(fixture.provisioning.scope().allowedDirectories())
                    .extracting(AuthorizedHostDirectory::workspaceId)
                    .doesNotContain(workspaceId);
            assertThat(reopened.workspaceAccess().find(TENANT, OWNER, workspaceId))
                    .isEmpty();
            assertThat(reopened.workspaceRegistry().find(PROJECT, workspaceId))
                    .get()
                    .extracting(entry -> entry.status())
                    .isEqualTo(HostWorkspaceRegistryStatus.REVOKED);
        }
    }

    @Test
    void unverifiablePathFailsClosedEvenWhenAccessStillExists() throws Exception {
        Path database = directory.resolve("unverifiable.db");
        Path initial = Files.createDirectory(directory.resolve("unverifiable-initial"));
        Path attached = Files.createDirectory(directory.resolve("unverifiable-attached"));
        WorkspaceId workspaceId;

        try (ProjectPersistenceAssembly first = persistence(database)) {
            Fixture fixture = fixture(initial, first);
            workspaceId = fixture.provisioning
                    .authorizeApprovedAttach(attached, mounted -> first.workspaceAccess()
                            .replace(new WorkspaceAccess(
                                    TENANT, OWNER, mounted.workspaceId(), WorkspaceAccessMode.DEVELOP)))
                    .directory()
                    .workspaceId();
        }
        Files.delete(attached);

        try (ProjectPersistenceAssembly reopened = persistence(database)) {
            Fixture fixture = fixture(initial, reopened);
            assertThat(fixture.provisioning.scope().allowedDirectories())
                    .extracting(AuthorizedHostDirectory::workspaceId)
                    .doesNotContain(workspaceId);
            assertThat(reopened.workspaceRegistry().find(PROJECT, workspaceId))
                    .get()
                    .extracting(entry -> entry.status())
                    .isEqualTo(HostWorkspaceRegistryStatus.DISABLED);
            assertThat(reopened.workspaceAccess().find(TENANT, OWNER, workspaceId))
                    .contains(new WorkspaceAccess(TENANT, OWNER, workspaceId, WorkspaceAccessMode.DEVELOP));
        }

        try (ProjectPersistenceAssembly reopenedAgain = persistence(database)) {
            Fixture fixture = fixture(initial, reopenedAgain);
            assertThat(fixture.provisioning.scope().allowedDirectories())
                    .extracting(AuthorizedHostDirectory::workspaceId)
                    .doesNotContain(workspaceId);
            assertThat(reopenedAgain.workspaceRegistry().find(PROJECT, workspaceId))
                    .get()
                    .extracting(entry -> entry.status())
                    .isEqualTo(HostWorkspaceRegistryStatus.DISABLED);
        }
    }

    @Test
    void unsafeLinkAtPersistedRegistryPathFailsClosed() throws Exception {
        Path database = directory.resolve("unsafe-link.db");
        Path initial = Files.createDirectory(directory.resolve("unsafe-link-initial"));
        Path attached = Files.createDirectory(directory.resolve("unsafe-link-attached"));
        Path outside = Files.createDirectory(directory.resolve("unsafe-link-outside"));
        WorkspaceId workspaceId;

        try (ProjectPersistenceAssembly first = persistence(database)) {
            Fixture fixture = fixture(initial, first);
            workspaceId = fixture.provisioning
                    .authorizeApprovedAttach(attached, mounted -> first.workspaceAccess()
                            .replace(new WorkspaceAccess(
                                    TENANT, OWNER, mounted.workspaceId(), WorkspaceAccessMode.DEVELOP)))
                    .directory()
                    .workspaceId();
        }
        Files.move(attached, directory.resolve("unsafe-link-original"));
        createUnsafeDirectoryLink(attached, outside);

        try {
            try (ProjectPersistenceAssembly reopened = persistence(database)) {
                Fixture fixture = fixture(initial, reopened);
                assertThat(fixture.provisioning.scope().allowedDirectories())
                        .extracting(AuthorizedHostDirectory::workspaceId)
                        .doesNotContain(workspaceId);
                assertThat(reopened.workspaceRegistry().find(PROJECT, workspaceId))
                        .get()
                        .extracting(HostWorkspaceRegistryEntry::status)
                        .isEqualTo(HostWorkspaceRegistryStatus.DISABLED);
                assertThat(reopened.workspaceAccess().find(TENANT, OWNER, workspaceId))
                        .contains(new WorkspaceAccess(TENANT, OWNER, workspaceId, WorkspaceAccessMode.DEVELOP));
            }
        } finally {
            Files.deleteIfExists(attached);
        }
    }

    @Test
    void persistedLocationIdentityMismatchFailsClosed() throws Exception {
        Path database = directory.resolve("identity-mismatch.db");
        Path initial = Files.createDirectory(directory.resolve("identity-mismatch-initial"));
        Path attached = Files.createDirectory(directory.resolve("identity-mismatch-attached"));
        WorkspaceId workspaceId;

        try (ProjectPersistenceAssembly first = persistence(database)) {
            Fixture fixture = fixture(initial, first);
            workspaceId = fixture.provisioning
                    .authorizeApprovedAttach(attached, mounted -> first.workspaceAccess()
                            .replace(new WorkspaceAccess(
                                    TENANT, OWNER, mounted.workspaceId(), WorkspaceAccessMode.READ)))
                    .directory()
                    .workspaceId();
            HostWorkspaceRegistryEntry persisted =
                    first.workspaceRegistry().find(PROJECT, workspaceId).orElseThrow();
            first.workspaceRegistry()
                    .update(
                            new HostWorkspaceRegistryEntry(
                                    persisted.projectId(),
                                    persisted.workspaceRef(),
                                    new WorkspaceLocationRef("mismatched-location-ref"),
                                    persisted.safeDisplayName(),
                                    persisted.source(),
                                    persisted.status(),
                                    persisted.realPath(),
                                    persisted.fingerprint(),
                                    persisted.createdAt(),
                                    persisted.validatedAt(),
                                    Optional.empty(),
                                    Optional.empty(),
                                    persisted.version() + 1),
                            persisted.version());
        }

        try (ProjectPersistenceAssembly reopened = persistence(database)) {
            Fixture fixture = fixture(initial, reopened);
            assertThat(fixture.provisioning.scope().allowedDirectories())
                    .extracting(AuthorizedHostDirectory::workspaceId)
                    .doesNotContain(workspaceId);
            assertThat(reopened.workspaceRegistry().find(PROJECT, workspaceId))
                    .get()
                    .extracting(HostWorkspaceRegistryEntry::status)
                    .isEqualTo(HostWorkspaceRegistryStatus.DISABLED);
            assertThat(reopened.workspaceAccess().find(TENANT, OWNER, workspaceId))
                    .contains(new WorkspaceAccess(TENANT, OWNER, workspaceId, WorkspaceAccessMode.READ));
        }
    }

    private static Fixture fixture(Path initialRoot, ProjectPersistenceAssembly persistence) throws Exception {
        Path realRoot = initialRoot.toRealPath();
        var projects = new InMemoryProjectStore();
        var workspaces = new InMemoryWorkspaceStore();
        var bindings = new InMemoryWorkspaceBindingStore();
        var locations = new HostWorkspaceLocationStore();
        TimeProvider time = () -> NOW;
        WorkspaceLocationRef locationRef = new WorkspaceLocationRef("m5-initial-location");
        WorkspaceBinding binding = WorkspaceBinding.provision(
                        new WorkspaceBindingId("m5-initial-binding"),
                        locationRef,
                        WorkspaceBindingMode.DIRECT,
                        OWNER,
                        WorkspaceCapabilitySet.readWriteFiles(),
                        WorkspacePermissionSet.readWrite(),
                        HostWorkspaceLocationStore.fingerprintFor(realRoot),
                        NOW)
                .activate(NOW);
        bindings.create(binding);
        locations.register(locationRef, realRoot);
        Workspace workspace = Workspace.provision(
                        new WorkspaceId("m5-initial-workspace"),
                        PROJECT,
                        WorkspacePurpose.PRIMARY,
                        new WorkspaceRoot(ProjectPath.root(), binding.id(), "local-guarded"),
                        WorkspaceRevision.initial(binding.rootFingerprint()),
                        NOW)
                .activate(NOW);
        workspaces.create(workspace);
        projects.create(Project.create(
                        PROJECT,
                        TENANT,
                        OWNER,
                        "m5-path-recovery",
                        "",
                        new ProjectConfigurationRef(new ProjectConfigurationId("m5-config").value(), "1"),
                        NOW,
                        java.util.Map.of())
                .assignDefaultWorkspace(workspace.id(), NOW));
        var service = new WorkspaceService(projects, workspaces, bindings, () -> "m5-generated", time);
        var initialScope = HostWorkspaceScope.initial(AuthorizedHostDirectory.of(workspace.id(), realRoot));
        return new Fixture(new AuthorizedWorkspaceProvisioning(
                PROJECT,
                workspaces,
                bindings,
                locations,
                service,
                OWNER,
                time,
                initialScope,
                persistence.workspaceRegistry(),
                "m5-initial"));
    }

    private static ProjectPersistenceAssembly persistence(Path database) {
        return ProjectPersistenceAssembly.open(
                ProjectPersistenceConfiguration.sqlite(database, "env://M5_TEST_KEY"),
                CLOCK,
                () -> "m5-persistence",
                new AesGcmModelContinuationProtector(
                        new SecretKeySpec(new byte[32], "AES"), new java.security.SecureRandom()));
    }

    private static void createUnsafeDirectoryLink(Path link, Path target) throws Exception {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.waitFor()).as(output).isZero();
            return;
        }
        Files.createSymbolicLink(link, target);
    }

    private record Fixture(AuthorizedWorkspaceProvisioning provisioning) {}
}
