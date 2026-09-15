package io.haifa.agent.project.hostworkspace.directory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.workspace.WorkspaceAccessMode;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AuthorizedDirectoryEntryTest {
    private static final Instant NOW = Instant.parse("2026-09-15T00:00:00Z");
    private static final ProjectId PROJECT = new ProjectId("project");
    private static final WorkspaceId WORKSPACE = new WorkspaceId("workspace");
    private static final TenantRef TENANT = new TenantRef("tenant");
    private static final PrincipalRef OWNER = new PrincipalRef("owner", "user");

    @Test
    void revalidatedAppliesOnlyToActiveEntries() {
        AuthorizedDirectoryEntry active = active();
        AuthorizedDirectoryEntry revalidated = active.revalidated(active.realPath(), "fp-2", NOW);
        assertThat(revalidated.status()).isEqualTo(AuthorizedDirectoryStatus.ACTIVE);
        assertThat(revalidated.version()).isEqualTo(active.version() + 1);

        AuthorizedDirectoryEntry revoked = active.revoke("TEST", NOW.plusSeconds(1));
        assertThatThrownBy(() -> revoked.revalidated(revoked.realPath(), "fp-2", NOW.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("revalidated");
    }

    @Test
    void reactivateAppliesOnlyToInactiveEntries() {
        AuthorizedDirectoryEntry active = active();
        assertThatThrownBy(() -> active.reactivate(active.realPath(), "fp-2", WorkspaceAccessMode.READ, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reactivated");

        AuthorizedDirectoryEntry disabled = active.disable("TEST", NOW.plusSeconds(1));
        AuthorizedDirectoryEntry reactivated =
                disabled.reactivate(disabled.realPath(), "fp-3", WorkspaceAccessMode.READ, NOW.plusSeconds(2));
        assertThat(reactivated.status()).isEqualTo(AuthorizedDirectoryStatus.ACTIVE);
        assertThat(reactivated.mode()).isEqualTo(WorkspaceAccessMode.READ);
        assertThat(reactivated.version()).isEqualTo(disabled.version() + 1);
    }

    @Test
    void transitionsRejectTimestampsBeforeValidatedAt() {
        AuthorizedDirectoryEntry active = active();
        Instant before = NOW.minusSeconds(1);

        assertThatThrownBy(() -> active.revalidated(active.realPath(), "fp-2", before))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("backwards");
        assertThatThrownBy(() -> active.disable("TEST", before))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("backwards");
        assertThatThrownBy(() -> active.revoke("TEST", before))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("backwards");

        AuthorizedDirectoryEntry disabled = active.disable("TEST", NOW.plusSeconds(1));
        assertThatThrownBy(() -> disabled.reactivate(disabled.realPath(), "fp-3", WorkspaceAccessMode.DEVELOP, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("backwards");
    }

    @Test
    void emptyReasonAndVersionInvariantsArePreserved() {
        AuthorizedDirectoryEntry active = active();
        assertThatThrownBy(() -> active.disable("  ", NOW.plusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThat(active.revalidated(active.realPath(), "fp-2", NOW.plusSeconds(1))
                        .version())
                .isEqualTo(active.version() + 1);
    }

    private static AuthorizedDirectoryEntry active() {
        return AuthorizedDirectoryEntry.active(
                PROJECT,
                WORKSPACE,
                TENANT,
                OWNER,
                WorkspaceAccessMode.DEVELOP,
                "workspace",
                Path.of("target", "authorized-directory-entry-test"),
                "physical-fingerprint-v1",
                NOW);
    }
}
