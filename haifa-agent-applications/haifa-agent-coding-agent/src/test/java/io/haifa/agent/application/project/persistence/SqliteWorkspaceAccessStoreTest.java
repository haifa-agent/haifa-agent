package io.haifa.agent.application.project.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.application.project.workspace.WorkspaceAccess;
import io.haifa.agent.application.project.workspace.WorkspaceAccessMode;
import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteWorkspaceAccessStoreTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC);
    private static final TenantRef TENANT = new TenantRef("tenant-a");
    private static final PrincipalRef ALICE = new PrincipalRef("alice", "user");
    private static final PrincipalRef BOB = new PrincipalRef("bob", "user");
    private static final WorkspaceId WORKSPACE = new WorkspaceId("workspace-a");

    @TempDir
    Path directory;

    @Test
    void minimalAccessSurvivesRestartAndKeepsOwnersIndependent() {
        Path database = directory.resolve("workspace-access.db");
        IdentifierGenerator ids = ids();
        try (ProjectPersistenceAssembly first = ProjectPersistenceAssembly.open(
                ProjectPersistenceConfiguration.sqliteUnprotected(database), CLOCK, ids, null)) {
            first.workspaceAccess()
                    .createIfAbsent(new WorkspaceAccess(TENANT, ALICE, WORKSPACE, WorkspaceAccessMode.READ));
            first.workspaceAccess().replace(new WorkspaceAccess(TENANT, BOB, WORKSPACE, WorkspaceAccessMode.DEVELOP));
        }

        try (ProjectPersistenceAssembly reopened = ProjectPersistenceAssembly.open(
                ProjectPersistenceConfiguration.sqliteUnprotected(database), CLOCK, ids, null)) {
            assertThat(reopened.workspaceAccess()
                            .createIfAbsent(new WorkspaceAccess(TENANT, ALICE, WORKSPACE, WorkspaceAccessMode.DEVELOP))
                            .mode())
                    .isEqualTo(WorkspaceAccessMode.READ);
            reopened.workspaceAccess()
                    .replace(new WorkspaceAccess(TENANT, ALICE, WORKSPACE, WorkspaceAccessMode.DEVELOP));
            assertThat(reopened.workspaceAccess().list(TENANT, ALICE))
                    .containsExactly(new WorkspaceAccess(TENANT, ALICE, WORKSPACE, WorkspaceAccessMode.DEVELOP));
            assertThat(reopened.workspaceAccess().list(TENANT, BOB))
                    .containsExactly(new WorkspaceAccess(TENANT, BOB, WORKSPACE, WorkspaceAccessMode.DEVELOP));
            assertThat(reopened.workspaceAccess().delete(TENANT, ALICE, WORKSPACE))
                    .isTrue();
            assertThat(reopened.workspaceAccess().find(TENANT, ALICE, WORKSPACE))
                    .isEmpty();
            assertThat(reopened.workspaceAccess().find(TENANT, BOB, WORKSPACE)).isPresent();
        }
    }

    private static IdentifierGenerator ids() {
        AtomicInteger sequence = new AtomicInteger();
        return () -> "workspace-access-" + sequence.incrementAndGet();
    }
}
