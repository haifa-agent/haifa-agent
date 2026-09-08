package io.haifa.agent.application.project.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.project.workspace.WorkspaceId;
import org.junit.jupiter.api.Test;

class WorkspaceAccessStoreTest {
    private static final TenantRef TENANT = new TenantRef("tenant-a");
    private static final PrincipalRef ALICE = new PrincipalRef("alice", "user");
    private static final PrincipalRef BOB = new PrincipalRef("bob", "user");
    private static final WorkspaceId WORKSPACE = new WorkspaceId("workspace-a");

    @Test
    void oneRelationPerOwnerAndWorkspaceIsAtomicallyReplacedAndDeleted() {
        WorkspaceAccessStore store = new InMemoryWorkspaceAccessStore();

        store.createIfAbsent(new WorkspaceAccess(TENANT, ALICE, WORKSPACE, WorkspaceAccessMode.READ));
        assertThat(store.createIfAbsent(new WorkspaceAccess(TENANT, ALICE, WORKSPACE, WorkspaceAccessMode.DEVELOP))
                        .mode())
                .isEqualTo(WorkspaceAccessMode.READ);
        store.replace(new WorkspaceAccess(TENANT, BOB, WORKSPACE, WorkspaceAccessMode.DEVELOP));
        store.replace(new WorkspaceAccess(TENANT, ALICE, WORKSPACE, WorkspaceAccessMode.DEVELOP));

        assertThat(store.list(TENANT, ALICE))
                .containsExactly(new WorkspaceAccess(TENANT, ALICE, WORKSPACE, WorkspaceAccessMode.DEVELOP));
        assertThat(store.list(TENANT, BOB))
                .containsExactly(new WorkspaceAccess(TENANT, BOB, WORKSPACE, WorkspaceAccessMode.DEVELOP));
        assertThat(store.delete(TENANT, ALICE, WORKSPACE)).isTrue();
        assertThat(store.find(TENANT, ALICE, WORKSPACE)).isEmpty();
        assertThat(store.find(TENANT, BOB, WORKSPACE)).isPresent();
    }

    @Test
    void readCannotAuthorizeDevelopmentAndMissingAccessFailsClosed() {
        WorkspaceAccessStore store = new InMemoryWorkspaceAccessStore();
        store.replace(new WorkspaceAccess(TENANT, ALICE, WORKSPACE, WorkspaceAccessMode.READ));

        assertThat(store.require(TENANT, ALICE, WORKSPACE, WorkspaceAccessMode.READ)
                        .mode())
                .isEqualTo(WorkspaceAccessMode.READ);
        assertThatThrownBy(() -> store.require(TENANT, ALICE, WORKSPACE, WorkspaceAccessMode.DEVELOP))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> store.require(TENANT, BOB, WORKSPACE, WorkspaceAccessMode.READ))
                .isInstanceOf(SecurityException.class);
    }
}
