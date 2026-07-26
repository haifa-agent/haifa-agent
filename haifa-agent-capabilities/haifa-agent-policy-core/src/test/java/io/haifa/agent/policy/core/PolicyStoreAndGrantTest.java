package io.haifa.agent.policy.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.policy.api.ApprovalGrant;
import io.haifa.agent.policy.api.ApprovalGrantId;
import io.haifa.agent.policy.api.ApprovalGrantQuery;
import io.haifa.agent.policy.api.ApprovalGrantState;
import io.haifa.agent.policy.api.ApprovalMode;
import io.haifa.agent.policy.api.ApprovalResponder;
import io.haifa.agent.policy.api.ApprovalReuseScope;
import io.haifa.agent.policy.api.ApprovalSemantics;
import io.haifa.agent.policy.api.ApprovalTargetRef;
import io.haifa.agent.policy.api.PolicyAction;
import io.haifa.agent.policy.api.PolicyContext;
import io.haifa.agent.policy.api.PolicyDecisionId;
import io.haifa.agent.policy.api.PolicySubject;
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
    private static final PolicySubject SUBJECT = new PolicySubject(TENANT, PRINCIPAL, "coding");
    private static final PolicyAction ACTION = new PolicyAction("workspace.file", "write");
    private static final ApprovalTargetRef TARGET =
            new ApprovalTargetRef("tool-call", "call", "1", "write", "sha256:args", "Write file");

    @Test
    void onceGrantCanBeConsumedExactlyOnceWithOptimisticVersion() {
        InMemoryPolicyStore store = new InMemoryPolicyStore();
        ApprovalGrant grant = grant(ApprovalReuseScope.ONCE);
        store.save(grant);

        ApprovalGrant consumed = store.consumeOnce(grant.id(), 0, NOW.plusSeconds(1));

        assertThat(consumed.state()).isEqualTo(ApprovalGrantState.CONSUMED);
        assertThat(consumed.version()).isEqualTo(1);
        assertThatThrownBy(() -> store.consumeOnce(grant.id(), 0, NOW.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version");
    }

    @Test
    void grantMatcherDoesNotCrossSubjectSessionOrTargetDigest() {
        ApprovalGrant grant = grant(ApprovalReuseScope.SESSION);
        ApprovalGrantMatcher matcher = new ApprovalGrantMatcher();

        assertThat(matcher.matches(grant, query(SUBJECT, "session", TARGET), NOW))
                .isTrue();
        assertThat(matcher.matches(grant, query(SUBJECT, "other", TARGET), NOW)).isFalse();
        assertThat(matcher.matches(
                        grant,
                        query(
                                SUBJECT,
                                "session",
                                new ApprovalTargetRef(
                                        "tool-call", "call", "1", "write", "sha256:changed", "Write file")),
                        NOW))
                .isFalse();
        assertThat(matcher.matches(
                        grant,
                        query(
                                new PolicySubject(TENANT, new PrincipalRef("other", "local"), "coding"),
                                "session",
                                TARGET),
                        NOW))
                .isFalse();
    }

    @Test
    void duplicateIdsWithDifferentContentAreRejected() {
        InMemoryPolicyStore store = new InMemoryPolicyStore();
        ApprovalGrant grant = grant(ApprovalReuseScope.ONCE);
        store.save(grant);
        store.save(grant);

        ApprovalGrant changed = new ApprovalGrant(
                grant.id(),
                grant.semantics(),
                grant.reuseScope(),
                grant.subject(),
                new PolicyAction("workspace.file", "delete"),
                grant.target(),
                grant.sessionRef(),
                grant.projectRef(),
                grant.projectTrustRef(),
                grant.securityConfigurationDigest(),
                grant.sourceDecisionId(),
                grant.sourceApprovalRequestRef(),
                grant.createdBy(),
                grant.createdAt(),
                grant.expiresAt(),
                grant.state(),
                grant.revokedAt(),
                grant.consumedAt(),
                grant.version());
        assertThatThrownBy(() -> store.save(changed)).isInstanceOf(IllegalStateException.class);
    }

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

    private static ApprovalGrant grant(ApprovalReuseScope scope) {
        return new ApprovalGrant(
                new ApprovalGrantId("grant-" + scope.name().toLowerCase()),
                ApprovalSemantics.CAPABILITY_CONFIRMATION,
                scope,
                SUBJECT,
                ACTION,
                TARGET,
                scope == ApprovalReuseScope.SESSION ? Optional.of("session") : Optional.empty(),
                scope == ApprovalReuseScope.PROJECT ? Optional.of("project") : Optional.empty(),
                scope == ApprovalReuseScope.PROJECT ? Optional.of(new ProjectTrustRef("trust")) : Optional.empty(),
                scope == ApprovalReuseScope.PROJECT ? Optional.of("sha256:config") : Optional.empty(),
                new PolicyDecisionId("decision"),
                "approval-request",
                new ApprovalResponder(TENANT, PRINCIPAL),
                NOW,
                Optional.of(NOW.plusSeconds(60)),
                ApprovalGrantState.ACTIVE,
                Optional.empty(),
                Optional.empty(),
                0);
    }

    private static ApprovalGrantQuery query(PolicySubject subject, String sessionRef, ApprovalTargetRef target) {
        return new ApprovalGrantQuery(
                subject,
                new PolicyContext(
                        Optional.of("project"),
                        Optional.of(sessionRef),
                        Optional.of("run"),
                        Optional.empty(),
                        ApprovalMode.ASK,
                        Optional.empty(),
                        Optional.empty()),
                ACTION,
                target);
    }
}
