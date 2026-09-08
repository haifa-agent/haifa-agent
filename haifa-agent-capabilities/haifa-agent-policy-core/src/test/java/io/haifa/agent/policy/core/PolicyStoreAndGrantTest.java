package io.haifa.agent.policy.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.policy.api.ProjectTrust;
import io.haifa.agent.policy.api.ProjectTrustRef;
import io.haifa.agent.policy.api.ProjectTrustState;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PolicyStoreAndGrantTest {
    private static final Instant NOW = Instant.parse("2026-07-26T00:00:00Z");
    private static final TenantRef TENANT = new TenantRef("tenant");
    private static final PrincipalRef PRINCIPAL = new PrincipalRef("user", "local");

    @Test
    void projectTrustRevocationUsesVersionAndStopsMatching() {
        InMemoryPolicyStore store = new InMemoryPolicyStore();
        ProjectTrust trust = new ProjectTrust(
                new ProjectTrustRef("trust"),
                TENANT,
                PRINCIPAL,
                "project",
                "project-id",
                "root-id",
                "sha256:config",
                "coding",
                ProjectTrustState.TRUSTED,
                NOW,
                Optional.empty(),
                Optional.empty(),
                0);
        store.save(trust);

        ProjectTrust revoked = store.revoke(trust.ref(), 0, NOW.plusSeconds(1));

        assertThat(revoked.state()).isEqualTo(ProjectTrustState.REVOKED);
        assertThat(revoked.matches(
                        TENANT,
                        PRINCIPAL,
                        "project",
                        "project-id",
                        "root-id",
                        "sha256:config",
                        "coding",
                        NOW.plusSeconds(2)))
                .isFalse();
        assertThatThrownBy(() -> store.revoke(trust.ref(), 0, NOW.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static ProjectTrust trust() {
        return new ProjectTrust(
                new ProjectTrustRef("trust"),
                TENANT,
                PRINCIPAL,
                "project",
                "project-id",
                "root-id",
                "sha256:config",
                "coding",
                ProjectTrustState.TRUSTED,
                NOW,
                Optional.of(NOW.plusSeconds(60)),
                Optional.empty(),
                0);
    }
}
