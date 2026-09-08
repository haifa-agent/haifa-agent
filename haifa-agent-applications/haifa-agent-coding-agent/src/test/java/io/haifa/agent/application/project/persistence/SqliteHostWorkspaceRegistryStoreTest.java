package io.haifa.agent.application.project.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.project.binding.WorkspaceLocationRef;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.hostworkspace.HostWorkspaceLocationStore;
import io.haifa.agent.project.hostworkspace.registry.HostWorkspaceRegistryEntry;
import io.haifa.agent.project.hostworkspace.registry.HostWorkspaceRegistrySource;
import io.haifa.agent.project.hostworkspace.registry.HostWorkspaceRegistryStatus;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.runtime.core.model.continuation.AesGcmModelContinuationProtector;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteHostWorkspaceRegistryStoreTest {
    private static final Instant NOW = Instant.parse("2026-09-06T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir
    Path directory;

    @Test
    void encryptedLocationAndRegistryFactsSurviveRestart() throws Exception {
        Path database = directory.resolve("workspace-registry.db");
        Path root = Files.createDirectories(directory.resolve("private-attached-root"))
                .toRealPath();
        ProjectId projectId = new ProjectId("project-registry");
        WorkspaceId workspaceRef = new WorkspaceId("workspace-registry-entry");
        var entry = HostWorkspaceRegistryEntry.active(
                projectId,
                workspaceRef,
                new WorkspaceLocationRef("location-registry-entry"),
                "attached-root",
                HostWorkspaceRegistrySource.APPROVED_ATTACH,
                root,
                HostWorkspaceLocationStore.fingerprintFor(root),
                NOW);

        try (ProjectPersistenceAssembly first = ProjectPersistenceAssembly.open(
                ProjectPersistenceConfiguration.sqlite(database, "env://TEST_KEY"),
                CLOCK,
                () -> "registry-first",
                protector())) {
            first.workspaceRegistry().create(entry);
        }

        assertThat(new String(Files.readAllBytes(database), StandardCharsets.UTF_8))
                .doesNotContain(root.toString(), "private-attached-root");
        try (ProjectPersistenceAssembly reopened = ProjectPersistenceAssembly.open(
                ProjectPersistenceConfiguration.sqlite(database, "env://TEST_KEY"),
                CLOCK,
                () -> "registry-second",
                protector())) {
            assertThat(reopened.workspaceRegistry().find(projectId, workspaceRef))
                    .contains(entry);
        }
    }

    @Test
    void corruptProtectedLocationIsDisabledAndNeverReturnedAsActive() throws Exception {
        Path database = directory.resolve("workspace-registry-corrupt.db");
        Path root = Files.createDirectories(directory.resolve("corrupt-root")).toRealPath();
        ProjectId projectId = new ProjectId("project-corrupt");
        WorkspaceId workspaceRef = new WorkspaceId("workspace-corrupt");
        var entry = HostWorkspaceRegistryEntry.active(
                projectId,
                workspaceRef,
                new WorkspaceLocationRef("location-corrupt"),
                "corrupt-root",
                HostWorkspaceRegistrySource.APPROVED_ATTACH,
                root,
                HostWorkspaceLocationStore.fingerprintFor(root),
                NOW);
        try (ProjectPersistenceAssembly first = ProjectPersistenceAssembly.open(
                ProjectPersistenceConfiguration.sqlite(database, "env://TEST_KEY"),
                CLOCK,
                () -> "corrupt-first",
                protector())) {
            first.workspaceRegistry().create(entry);
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            connection
                    .createStatement()
                    .executeUpdate("UPDATE coding_workspace_registry SET location_ciphertext = X'00'"
                            + " WHERE workspace_ref = 'workspace-corrupt'");
        }

        try (ProjectPersistenceAssembly reopened = ProjectPersistenceAssembly.open(
                ProjectPersistenceConfiguration.sqlite(database, "env://TEST_KEY"),
                CLOCK,
                () -> "corrupt-second",
                protector())) {
            assertThat(reopened.workspaceRegistry().find(projectId, workspaceRef))
                    .isEmpty();
            assertThat(reopened.workspaceRegistry().list(projectId)).isEmpty();
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var result = connection
                        .createStatement()
                        .executeQuery("SELECT status, revocation_reason_code FROM coding_workspace_registry"
                                + " WHERE workspace_ref = 'workspace-corrupt'")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString("status")).isEqualTo(HostWorkspaceRegistryStatus.DISABLED.name());
            assertThat(result.getString("revocation_reason_code")).isEqualTo("LOCATION_DECRYPTION_FAILED");
        }
    }

    @Test
    void freshRegistrySchemaKeepsPhysicalFingerprintAndHasNoPermissionColumn() throws Exception {
        Path database = directory.resolve("workspace-registry-schema.db");
        try (ProjectPersistenceAssembly ignored = ProjectPersistenceAssembly.open(
                ProjectPersistenceConfiguration.sqlite(database, "env://TEST_KEY"),
                CLOCK,
                () -> "registry-schema",
                protector())) {
            // Opening a fresh store applies the clean development baseline.
        }

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var columns =
                        connection.createStatement().executeQuery("PRAGMA table_info(coding_workspace_registry)")) {
            var names = new java.util.ArrayList<String>();
            while (columns.next()) {
                names.add(columns.getString("name"));
            }
            assertThat(names).contains("fingerprint").doesNotContain("permission", "physical_fingerprint");
        }
    }

    private static AesGcmModelContinuationProtector protector() {
        return new AesGcmModelContinuationProtector(
                new SecretKeySpec(new byte[32], "AES"), new java.security.SecureRandom());
    }
}
