package io.haifa.agent.policy.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PolicyContractTest {
    private static final Instant NOW = Instant.parse("2026-07-26T00:00:00Z");
    private static final TenantRef TENANT = new TenantRef("tenant");
    private static final PrincipalRef PRINCIPAL = new PrincipalRef("user", "local");

    @Test
    void askRequiresChallengeAndOtherEffectsRejectIt() {
        assertThatThrownBy(() -> decision(PolicyEffect.ASK, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> decision(PolicyEffect.ALLOW, Optional.of(PolicyChallenge.APPROVAL)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(decision(PolicyEffect.ASK, Optional.of(PolicyChallenge.REAUTHENTICATE))
                        .challenge())
                .contains(PolicyChallenge.REAUTHENTICATE);
    }

    @Test
    void businessAuthorizationOnlyAllowsOnceAndRequiresAuthority() {
        assertThatThrownBy(() -> approval(
                        ApprovalSemantics.BUSINESS_AUTHORIZATION,
                        Set.of(ApprovalReuseScope.SESSION),
                        Optional.of(new ApprovalAuthorityRequirementRef("enterprise", "manager", "1"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> approval(
                        ApprovalSemantics.BUSINESS_AUTHORIZATION, Set.of(ApprovalReuseScope.ONCE), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(approval(
                                ApprovalSemantics.BUSINESS_AUTHORIZATION,
                                Set.of(ApprovalReuseScope.ONCE),
                                Optional.of(new ApprovalAuthorityRequirementRef("enterprise", "manager", "1")))
                        .allowedReuseScopes())
                .containsExactly(ApprovalReuseScope.ONCE);
    }

    @Test
    void businessAuthorizationCannotBecomeAGrant() {
        assertThatThrownBy(() -> grant(
                        ApprovalSemantics.BUSINESS_AUTHORIZATION,
                        ApprovalReuseScope.ONCE,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("business authorization");
    }

    @Test
    void scopedGrantsRequireTheirScopeIdentity() {
        assertThatThrownBy(() -> grant(
                        ApprovalSemantics.CAPABILITY_CONFIRMATION,
                        ApprovalReuseScope.SESSION,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> grant(
                        ApprovalSemantics.CAPABILITY_CONFIRMATION,
                        ApprovalReuseScope.PROJECT,
                        Optional.empty(),
                        Optional.of("project"),
                        Optional.empty(),
                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void projectTrustFailsClosedOnConfigurationOrSubjectDrift() {
        ProjectTrust trust = new ProjectTrust(
                new ProjectTrustRef("trust"),
                TENANT,
                PRINCIPAL,
                "project",
                "project-identity",
                "root-identity",
                "sha256:config",
                "coding",
                ProjectTrustState.TRUSTED,
                NOW,
                Optional.of(NOW.plusSeconds(60)),
                Optional.empty(),
                0);

        assertThat(trust.matches(
                        TENANT,
                        PRINCIPAL,
                        "project",
                        "project-identity",
                        "root-identity",
                        "sha256:config",
                        "coding",
                        NOW))
                .isTrue();
        assertThat(trust.matches(
                        TENANT,
                        PRINCIPAL,
                        "project",
                        "project-identity",
                        "root-identity",
                        "sha256:changed",
                        "coding",
                        NOW))
                .isFalse();
        assertThat(trust.matches(
                        new TenantRef("other"),
                        PRINCIPAL,
                        "project",
                        "project-identity",
                        "root-identity",
                        "sha256:config",
                        "coding",
                        NOW))
                .isFalse();
    }

    @Test
    void safeTextIsBounded() {
        assertThatThrownBy(() -> new PolicyResource("file", "workspace:a", Optional.empty(), "x".repeat(513)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static PolicyDecision decision(PolicyEffect effect, Optional<PolicyChallenge> challenge) {
        return new PolicyDecision(
                new PolicyDecisionId("decision"),
                effect,
                challenge,
                "REASON",
                "Safe explanation",
                new PolicySnapshotRef("snapshot"),
                Optional.empty(),
                NOW);
    }

    private static ApprovalRequestContext approval(
            ApprovalSemantics semantics,
            Set<ApprovalReuseScope> scopes,
            Optional<ApprovalAuthorityRequirementRef> authority) {
        return new ApprovalRequestContext(
                new PolicyDecisionId("decision"),
                semantics,
                scopes,
                new ApprovalRequester(TENANT, PRINCIPAL),
                target(),
                authority,
                NOW,
                NOW.plusSeconds(60),
                Optional.empty());
    }

    private static ApprovalGrant grant(
            ApprovalSemantics semantics,
            ApprovalReuseScope scope,
            Optional<String> sessionRef,
            Optional<String> projectRef,
            Optional<ProjectTrustRef> trustRef,
            Optional<String> configurationDigest) {
        return new ApprovalGrant(
                new ApprovalGrantId("grant"),
                semantics,
                scope,
                new PolicySubject(TENANT, PRINCIPAL, "coding"),
                new PolicyAction("workspace.file", "write"),
                target(),
                sessionRef,
                projectRef,
                trustRef,
                configurationDigest,
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

    private static ApprovalTargetRef target() {
        return new ApprovalTargetRef(
                "tool-call", "call-1", "1", "write", "sha256:arguments", "Write one workspace file");
    }
}
