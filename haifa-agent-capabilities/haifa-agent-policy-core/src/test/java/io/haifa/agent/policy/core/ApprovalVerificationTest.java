package io.haifa.agent.policy.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.policy.api.ApprovalAuthorityDecision;
import io.haifa.agent.policy.api.ApprovalAuthorityRequirementRef;
import io.haifa.agent.policy.api.ApprovalAuthorityStatus;
import io.haifa.agent.policy.api.ApprovalRequestContext;
import io.haifa.agent.policy.api.ApprovalRequester;
import io.haifa.agent.policy.api.ApprovalResponder;
import io.haifa.agent.policy.api.ApprovalReuseScope;
import io.haifa.agent.policy.api.ApprovalSemantics;
import io.haifa.agent.policy.api.ApprovalTargetRef;
import io.haifa.agent.policy.api.ApprovalTargetStatus;
import io.haifa.agent.policy.api.ApprovalTargetValidation;
import io.haifa.agent.policy.api.ApprovalVerification;
import io.haifa.agent.policy.api.PolicyDecisionId;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ApprovalVerificationTest {
    private static final Instant NOW = Instant.parse("2026-07-26T00:00:00Z");
    private static final TenantRef TENANT = new TenantRef("tenant");
    private static final PrincipalRef REQUESTER = new PrincipalRef("requester", "user");
    private static final ApprovalTargetRef TARGET =
            new ApprovalTargetRef("tool-call", "call", "1", "write", "sha256:args", "Write file");

    @Test
    void localCapabilityConfirmationRequiresSameTenantAndPrincipal() {
        DefaultApprovalVerificationService service = service(Map.of(), currentTarget());
        ApprovalRequestContext request = request(ApprovalSemantics.CAPABILITY_CONFIRMATION, Optional.empty());

        assertThat(service.verify(request, new ApprovalResponder(TENANT, REQUESTER))
                        .accepted())
                .isTrue();
        assertThat(service.verify(request, new ApprovalResponder(TENANT, new PrincipalRef("other", "user")))
                        .accepted())
                .isFalse();
        assertThat(service.verify(request, new ApprovalResponder(new TenantRef("other"), REQUESTER))
                        .accepted())
                .isFalse();
    }

    @Test
    void missingOrFailingTargetValidatorFailsClosed() {
        ApprovalRequestContext request = request(ApprovalSemantics.CAPABILITY_CONFIRMATION, Optional.empty());

        assertThat(service(Map.of(), Map.of())
                        .verify(request, new ApprovalResponder(TENANT, REQUESTER))
                        .reasonCode())
                .isEqualTo("TARGET_VALIDATOR_UNAVAILABLE");
        assertThat(service(Map.of(), Map.of("tool-call", target -> {
                            throw new IllegalStateException("unsafe detail");
                        }))
                        .verify(request, new ApprovalResponder(TENANT, REQUESTER))
                        .reasonCode())
                .isEqualTo("TARGET_VALIDATION_FAILED");
    }

    @Test
    void businessAuthorizationRequiresRegisteredAuthorityProvider() {
        ApprovalAuthorityRequirementRef requirement =
                new ApprovalAuthorityRequirementRef("enterprise", "manager-of-requester", "1");
        ApprovalRequestContext request = request(ApprovalSemantics.BUSINESS_AUTHORIZATION, Optional.of(requirement));

        ApprovalVerification missing =
                service(Map.of(), currentTarget()).verify(request, new ApprovalResponder(TENANT, REQUESTER));
        assertThat(missing.accepted()).isFalse();
        assertThat(missing.reasonCode()).isEqualTo("AUTHORITY_VERIFIER_UNAVAILABLE");

        ApprovalVerification accepted = service(
                        Map.of(
                                "enterprise",
                                (authority, requester, responder, target) ->
                                        new ApprovalAuthorityDecision(ApprovalAuthorityStatus.ELIGIBLE, "ELIGIBLE")),
                        currentTarget())
                .verify(request, new ApprovalResponder(TENANT, new PrincipalRef("manager", "user")));
        assertThat(accepted.accepted()).isTrue();
    }

    @Test
    void staleBusinessTargetFailsBeforeAuthority() {
        ApprovalRequestContext request = request(
                ApprovalSemantics.BUSINESS_AUTHORIZATION,
                Optional.of(new ApprovalAuthorityRequirementRef("enterprise", "manager", "1")));
        ApprovalVerification result = service(
                        Map.of(
                                "enterprise",
                                (authority, requester, responder, target) ->
                                        new ApprovalAuthorityDecision(ApprovalAuthorityStatus.ELIGIBLE, "ELIGIBLE")),
                        Map.of(
                                "tool-call",
                                target -> new ApprovalTargetValidation(ApprovalTargetStatus.STALE, "TARGET_STALE")))
                .verify(request, new ApprovalResponder(TENANT, REQUESTER));

        assertThat(result.accepted()).isFalse();
        assertThat(result.reasonCode()).isEqualTo("TARGET_STALE");
    }

    private static DefaultApprovalVerificationService service(
            Map<String, io.haifa.agent.policy.api.ApprovalAuthorityVerifier> authorities,
            Map<String, io.haifa.agent.policy.api.ApprovalTargetValidator> targets) {
        return new DefaultApprovalVerificationService(new LocalCapabilityAuthorityVerifier(), authorities, targets);
    }

    private static Map<String, io.haifa.agent.policy.api.ApprovalTargetValidator> currentTarget() {
        return Map.of(
                "tool-call", target -> new ApprovalTargetValidation(ApprovalTargetStatus.CURRENT, "TARGET_CURRENT"));
    }

    private static ApprovalRequestContext request(
            ApprovalSemantics semantics, Optional<ApprovalAuthorityRequirementRef> authority) {
        return new ApprovalRequestContext(
                new PolicyDecisionId("decision"),
                semantics,
                Set.of(ApprovalReuseScope.ONCE),
                new ApprovalRequester(TENANT, REQUESTER),
                TARGET,
                authority,
                NOW,
                NOW.plusSeconds(60),
                Optional.empty());
    }
}
