package io.haifa.agent.application.project.persistence;

import static org.assertj.core.api.Assertions.assertThat;

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
import io.haifa.agent.project.hostworkspace.scope.AuthorizedHostDirectory;
import io.haifa.agent.project.hostworkspace.scope.AuthorizedWorkspaceProvisioning;
import io.haifa.agent.project.hostworkspace.scope.HostDirectoryIdentity;
import io.haifa.agent.project.hostworkspace.scope.HostWorkspaceScope;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspaceAccessMode;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.project.workspace.WorkspaceRevision;
import io.haifa.agent.runtime.core.model.continuation.AesGcmModelContinuationProtector;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuthorizedDirectoryRecoveryIT {
    private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final TenantRef TENANT = new TenantRef("local");
    private static final PrincipalRef OWNER = new PrincipalRef("operator", "user");
    private static final ProjectId PROJECT = new ProjectId("m5-path-recovery");
    private static final WorkspaceId INITIAL_WORKSPACE = new WorkspaceId("m5-initial-workspace");

    @TempDir
    Path directory;

    @Test
    void unchangedRootRestoresOnRestart() throws Exception {
        Path database = directory.resolve("unchanged.db");
        Path initial = Files.createDirectory(directory.resolve("unchanged-initial"));
        Path attached = Files.createDirectory(directory.resolve("unchanged-attached"));
        WorkspaceId workspaceId;

        try (ProjectPersistenceAssembly first = persistence(database)) {
            Fixture fixture = fixture(initial, first);
            workspaceId = fixture.provisioning
                    .authorizeApprovedAttach(attached, WorkspaceAccessMode.DEVELOP)
                    .directory()
                    .workspaceId();
        }

        try (ProjectPersistenceAssembly reopened = persistence(database)) {
            Fixture fixture = fixture(initial, reopened);
            assertThat(fixture.provisioning.scope().allowedDirectories())
                    .extracting(AuthorizedHostDirectory::workspaceId)
                    .contains(workspaceId);
            assertThat(reopened.authorizedDirectories().find(PROJECT, workspaceId))
                    .get()
                    .satisfies(entry -> {
                        assertThat(entry.status()).isEqualTo(AuthorizedDirectoryStatus.ACTIVE);
                        assertThat(entry.mode()).isEqualTo(WorkspaceAccessMode.DEVELOP);
                    });
        }
    }

    @Test
    void physicalReplacementAtTheSameCanonicalPathFailsClosedAndRequiresExplicitReauthorization() throws Exception {
        Path database = directory.resolve("same-path.db");
        Path initial = Files.createDirectory(directory.resolve("same-path-initial"));
        Path attached = Files.createDirectory(directory.resolve("same-path-attached"));
        WorkspaceId workspaceId;
        String originalPhysicalFingerprint;

        try (ProjectPersistenceAssembly first = persistence(database)) {
            Fixture fixture = fixture(initial, first);
            var result = fixture.provisioning.authorizeApprovedAttach(attached, WorkspaceAccessMode.DEVELOP);
            workspaceId = result.directory().workspaceId();
            originalPhysicalFingerprint = first.authorizedDirectories()
                    .find(PROJECT, workspaceId)
                    .orElseThrow()
                    .physicalFingerprint();
        }

        Files.move(attached, directory.resolve("same-path-original"));
        Files.createDirectory(attached);
        String replacementFingerprint = HostDirectoryIdentity.resolve(attached).physicalFingerprint();
        assertThat(replacementFingerprint).isNotEqualTo(originalPhysicalFingerprint);

        try (ProjectPersistenceAssembly reopened = persistence(database)) {
            Fixture fixture = fixture(initial, reopened);
            assertThat(fixture.provisioning.scope().allowedDirectories())
                    .extracting(AuthorizedHostDirectory::workspaceId)
                    .doesNotContain(workspaceId);
            assertThat(reopened.authorizedDirectories().find(PROJECT, workspaceId))
                    .get()
                    .satisfies(entry -> {
                        assertThat(entry.status()).isEqualTo(AuthorizedDirectoryStatus.DISABLED);
                        assertThat(entry.physicalFingerprint()).isEqualTo(originalPhysicalFingerprint);
                    });

            var reauthorized = fixture.provisioning.authorizeApprovedAttach(attached, WorkspaceAccessMode.DEVELOP);
            assertThat(reauthorized.directory().workspaceId()).isEqualTo(workspaceId);
            assertThat(reopened.authorizedDirectories().find(PROJECT, workspaceId))
                    .get()
                    .satisfies(entry -> {
                        assertThat(entry.status()).isEqualTo(AuthorizedDirectoryStatus.ACTIVE);
                        assertThat(entry.physicalFingerprint()).isEqualTo(replacementFingerprint);
                    });
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
                    .authorizeApprovedAttach(original, WorkspaceAccessMode.READ)
                    .directory()
                    .workspaceId();
        }

        try (ProjectPersistenceAssembly reopened = persistence(database)) {
            Fixture fixture = fixture(initial, reopened);
            WorkspaceId differentId = fixture.provisioning
                    .authorizeApprovedAttach(different, WorkspaceAccessMode.READ)
                    .directory()
                    .workspaceId();
            assertThat(differentId).isNotEqualTo(originalId);
            assertThat(reopened.authorizedDirectories().find(PROJECT, differentId))
                    .get()
                    .extracting(AuthorizedDirectoryEntry::status)
                    .isEqualTo(AuthorizedDirectoryStatus.ACTIVE);
            assertThat(reopened.authorizedDirectories().find(PROJECT, originalId))
                    .get()
                    .satisfies(entry -> assertThat(entry.mode()).isEqualTo(WorkspaceAccessMode.READ));
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
                    .authorizeApprovedAttach(attached, WorkspaceAccessMode.DEVELOP)
                    .directory()
                    .workspaceId();
            fixture.provisioning.revoke(workspaceId);
        }

        try (ProjectPersistenceAssembly reopened = persistence(database)) {
            Fixture fixture = fixture(initial, reopened);
            assertThat(fixture.provisioning.scope().allowedDirectories())
                    .extracting(AuthorizedHostDirectory::workspaceId)
                    .doesNotContain(workspaceId);
            assertThat(reopened.authorizedDirectories().find(PROJECT, workspaceId))
                    .get()
                    .extracting(AuthorizedDirectoryEntry::status)
                    .isEqualTo(AuthorizedDirectoryStatus.REVOKED);
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
                    .authorizeApprovedAttach(attached, WorkspaceAccessMode.DEVELOP)
                    .directory()
                    .workspaceId();
        }
        Files.delete(attached);

        try (ProjectPersistenceAssembly reopened = persistence(database)) {
            Fixture fixture = fixture(initial, reopened);
            assertThat(fixture.provisioning.scope().allowedDirectories())
                    .extracting(AuthorizedHostDirectory::workspaceId)
                    .doesNotContain(workspaceId);
            assertThat(reopened.authorizedDirectories().find(PROJECT, workspaceId))
                    .get()
                    .extracting(AuthorizedDirectoryEntry::status)
                    .isEqualTo(AuthorizedDirectoryStatus.DISABLED);
        }

        try (ProjectPersistenceAssembly reopenedAgain = persistence(database)) {
            Fixture fixture = fixture(initial, reopenedAgain);
            assertThat(fixture.provisioning.scope().allowedDirectories())
                    .extracting(AuthorizedHostDirectory::workspaceId)
                    .doesNotContain(workspaceId);
            assertThat(reopenedAgain.authorizedDirectories().find(PROJECT, workspaceId))
                    .get()
                    .extracting(AuthorizedDirectoryEntry::status)
                    .isEqualTo(AuthorizedDirectoryStatus.DISABLED);
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
                    .authorizeApprovedAttach(attached, WorkspaceAccessMode.DEVELOP)
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
                assertThat(reopened.authorizedDirectories().find(PROJECT, workspaceId))
                        .get()
                        .extracting(AuthorizedDirectoryEntry::status)
                        .isEqualTo(AuthorizedDirectoryStatus.DISABLED);
            }
        } finally {
            Files.deleteIfExists(attached);
        }
    }

    private static Fixture fixture(Path initialRoot, ProjectPersistenceAssembly persistence) throws Exception {
        Path realRoot = initialRoot.toRealPath();
        var projects = new InMemoryProjectStore();
        var workspaces = new InMemoryWorkspaceStore();
        var locations = new HostWorkspaceLocationStore();
        TimeProvider time = () -> NOW;
        locations.register(INITIAL_WORKSPACE, realRoot);
        Workspace workspace = Workspace.provision(INITIAL_WORKSPACE, PROJECT, WorkspaceRevision.initial("initial"), NOW)
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
        var service = new WorkspaceService(projects, workspaces, time);
        var initialScope = HostWorkspaceScope.initial(AuthorizedHostDirectory.of(workspace.id(), realRoot));
        return new Fixture(new AuthorizedWorkspaceProvisioning(
                PROJECT,
                workspaces,
                locations,
                service,
                TENANT,
                OWNER,
                time,
                initialScope,
                persistence.authorizedDirectories(),
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
