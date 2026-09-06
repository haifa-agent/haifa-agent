package io.haifa.agent.policy.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.policy.api.ApprovalGrantCreationRequest;
import io.haifa.agent.policy.api.ApprovalGrantId;
import io.haifa.agent.policy.api.ApprovalGrantState;
import io.haifa.agent.policy.api.ApprovalMode;
import io.haifa.agent.policy.api.ApprovalRequestContext;
import io.haifa.agent.policy.api.ApprovalRequester;
import io.haifa.agent.policy.api.ApprovalResponder;
import io.haifa.agent.policy.api.ApprovalReuseScope;
import io.haifa.agent.policy.api.ApprovalSemantics;
import io.haifa.agent.policy.api.ApprovalTargetRef;
import io.haifa.agent.policy.api.ApprovalVerification;
import io.haifa.agent.policy.api.AuthorizationClassification;
import io.haifa.agent.policy.api.AuthorizationResult;
import io.haifa.agent.policy.api.AuthorizationSource;
import io.haifa.agent.policy.api.PolicyAction;
import io.haifa.agent.policy.api.PolicyChallenge;
import io.haifa.agent.policy.api.PolicyContext;
import io.haifa.agent.policy.api.PolicyDecision;
import io.haifa.agent.policy.api.PolicyDecisionId;
import io.haifa.agent.policy.api.PolicyEffect;
import io.haifa.agent.policy.api.PolicyRequest;
import io.haifa.agent.policy.api.PolicyRequestDigest;
import io.haifa.agent.policy.api.PolicyResource;
import io.haifa.agent.policy.api.PolicyRisk;
import io.haifa.agent.policy.api.PolicyRiskLevel;
import io.haifa.agent.policy.api.PolicySideEffect;
import io.haifa.agent.policy.api.PolicySnapshotRef;
import io.haifa.agent.policy.api.PolicySubject;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SharedAuthorizationContractTest {
    private static final Instant NOW = Instant.parse("2026-09-06T00:00:00Z");
    private static final TenantRef TENANT = new TenantRef("tenant");
    private static final PrincipalRef PRINCIPAL = new PrincipalRef("principal", "user");
    private static final ApprovalTargetRef TARGET =
            new ApprovalTargetRef("tool", "call-1", "definition-v1", "invoke", "sha256:args", "Execute tool");

    @Test
    void mapsPolicyProtocolAndExecutionFactsWithoutConflatingThem() {
        var service = service(new InMemoryPolicyStore());

        assertThat(service.authorize(decision(PolicyEffect.DENY, "haifa-coding-agent"), TARGET, Optional.empty()))
                .extracting(AuthorizationResult::classification, AuthorizationResult::source)
                .containsExactly(AuthorizationClassification.HARD_DENY, AuthorizationSource.POLICY);
        assertThat(service.authorize(decision(PolicyEffect.ASK, "haifa-coding-agent"), TARGET, Optional.empty()))
                .extracting(AuthorizationResult::classification, AuthorizationResult::source)
                .containsExactly(AuthorizationClassification.REQUIRES_APPROVAL, AuthorizationSource.POLICY);
        assertThat(service.authorize(decision(PolicyEffect.ALLOW, "haifa-coding-agent"), TARGET, Optional.empty()))
                .extracting(AuthorizationResult::classification, AuthorizationResult::source)
                .containsExactly(AuthorizationClassification.ALLOW, AuthorizationSource.POLICY);

        assertThat(AuthorizationResult.protocolError("WORKSPACE_PROTOCOL_REQUIRED", "Use structured workspace input"))
                .extracting(AuthorizationResult::classification, AuthorizationResult::source)
                .containsExactly(AuthorizationClassification.PROTOCOL_ERROR, AuthorizationSource.PROTOCOL);
        assertThat(AuthorizationResult.executionOutcome("PROCESS_EXIT_NON_ZERO", "The process exited unsuccessfully"))
                .extracting(AuthorizationResult::classification, AuthorizationResult::source)
                .containsExactly(AuthorizationClassification.EXECUTION_OUTCOME, AuthorizationSource.EXECUTION);
    }

    @Test
    void approvedInteractionCreatesAnExactGrantAndReasonAwareRevocationStopsReuse() {
        InMemoryPolicyStore store = new InMemoryPolicyStore();
        var service = service(store);
        PolicyDecision decision = decision(PolicyEffect.ASK, "haifa-coding-agent");
        ApprovalRequestContext approval = approval(decision);

        var grant = service.createGrant(new ApprovalGrantCreationRequest(
                decision,
                approval,
                new ApprovalVerification(true, "APPROVAL_VERIFIED"),
                "request-1",
                "response-1",
                new ApprovalResponder(TENANT, PRINCIPAL),
                ApprovalReuseScope.SESSION,
                NOW.plusSeconds(300)));

        assertThat(grant.state()).isEqualTo(ApprovalGrantState.ACTIVE);
        assertThat(grant.sourceApprovalRequestRef()).isEqualTo("request-1");
        assertThat(grant.sourceApprovalResponseRef()).isEqualTo("response-1");
        assertThat(service.authorize(decision, TARGET, Optional.empty()))
                .extracting(AuthorizationResult::classification, AuthorizationResult::source)
                .containsExactly(AuthorizationClassification.ALLOW, AuthorizationSource.GRANT);

        var revocation = service.revoke(grant.id(), grant.version(), "WORKSPACE_ACCESS_REVOKED");

        assertThat(revocation.reasonCode()).isEqualTo("WORKSPACE_ACCESS_REVOKED");
        assertThat(store.find(grant.id()).orElseThrow().revocationReasonCode()).contains("WORKSPACE_ACCESS_REVOKED");
        assertThat(service.authorize(decision, TARGET, Optional.empty()).classification())
                .isEqualTo(AuthorizationClassification.REQUIRES_APPROVAL);
    }

    @Test
    void aCodingGrantCannotAuthorizeThePersonalAssistantProduct() {
        InMemoryPolicyStore store = new InMemoryPolicyStore();
        var service = service(store);
        PolicyDecision codingDecision = decision(PolicyEffect.ASK, "haifa-coding-agent");
        service.createGrant(new ApprovalGrantCreationRequest(
                codingDecision,
                approval(codingDecision),
                new ApprovalVerification(true, "APPROVAL_VERIFIED"),
                "request-1",
                "response-1",
                new ApprovalResponder(TENANT, PRINCIPAL),
                ApprovalReuseScope.SESSION,
                NOW.plusSeconds(300)));

        AuthorizationResult personal =
                service.authorize(decision(PolicyEffect.ASK, "haifa-personal-assistant"), TARGET, Optional.empty());

        assertThat(personal.classification()).isEqualTo(AuthorizationClassification.REQUIRES_APPROVAL);
        assertThat(personal.grantId()).isEmpty();
    }

    @Test
    void codingAndPersonalAssistantProductsShareTheSameSafeClassificationContract() {
        var service = service(new InMemoryPolicyStore());

        for (String productId : Set.of("haifa-coding-agent", "haifa-personal-assistant")) {
            assertThat(service.authorize(decision(PolicyEffect.DENY, productId), TARGET, Optional.empty())
                            .classification())
                    .isEqualTo(AuthorizationClassification.HARD_DENY);
            assertThat(service.authorize(decision(PolicyEffect.ASK, productId), TARGET, Optional.empty())
                            .classification())
                    .isEqualTo(AuthorizationClassification.REQUIRES_APPROVAL);
            assertThat(service.authorize(decision(PolicyEffect.ALLOW, productId), TARGET, Optional.empty())
                            .classification())
                    .isEqualTo(AuthorizationClassification.ALLOW);
        }
    }

    @Test
    void unverifiedOrBusinessApprovalCannotCreateReusableGrant() {
        var service = service(new InMemoryPolicyStore());
        PolicyDecision decision = decision(PolicyEffect.ASK, "haifa-coding-agent");

        assertThatThrownBy(() -> service.createGrant(new ApprovalGrantCreationRequest(
                        decision,
                        approval(decision),
                        new ApprovalVerification(false, "TARGET_STALE"),
                        "request-1",
                        "response-1",
                        new ApprovalResponder(TENANT, PRINCIPAL),
                        ApprovalReuseScope.ONCE,
                        NOW.plusSeconds(300))))
                .isInstanceOf(IllegalArgumentException.class);

        ApprovalRequestContext business = new ApprovalRequestContext(
                decision.id(),
                ApprovalSemantics.BUSINESS_AUTHORIZATION,
                Set.of(ApprovalReuseScope.ONCE),
                new ApprovalRequester(TENANT, PRINCIPAL),
                TARGET,
                Optional.of(new io.haifa.agent.policy.api.ApprovalAuthorityRequirementRef("business", "owner", "1")),
                NOW,
                Optional.of(NOW.plusSeconds(300)),
                Optional.empty());
        assertThatThrownBy(() -> service.createGrant(new ApprovalGrantCreationRequest(
                        decision,
                        business,
                        new ApprovalVerification(true, "APPROVAL_VERIFIED"),
                        "request-1",
                        "response-1",
                        new ApprovalResponder(TENANT, PRINCIPAL),
                        ApprovalReuseScope.ONCE,
                        NOW.plusSeconds(300))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void approvalTargetRejectsHostAbsolutePathIdentity() {
        assertThatThrownBy(() -> new ApprovalTargetRef(
                        "workspace", "C:\\secret\\repo", "1", "attach", "sha256:root", "Attach workspace"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host absolute path");
    }

    private static DefaultApprovalGrantService service(InMemoryPolicyStore store) {
        AtomicInteger ids = new AtomicInteger();
        return new DefaultApprovalGrantService(
                store,
                store,
                new ApprovalGrantMatcher(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> new ApprovalGrantId("grant-" + ids.incrementAndGet()));
    }

    private static ApprovalRequestContext approval(PolicyDecision decision) {
        return new ApprovalRequestContext(
                decision.id(),
                ApprovalSemantics.CAPABILITY_CONFIRMATION,
                Set.of(ApprovalReuseScope.ONCE, ApprovalReuseScope.SESSION),
                new ApprovalRequester(TENANT, PRINCIPAL),
                TARGET,
                Optional.empty(),
                NOW,
                Optional.of(NOW.plusSeconds(300)),
                Optional.empty());
    }

    private static PolicyDecision decision(PolicyEffect effect, String productId) {
        PolicyRequest request = new PolicyRequest(
                new PolicySubject(TENANT, PRINCIPAL, productId),
                new PolicyContext(
                        Optional.empty(),
                        Optional.of("session-1"),
                        Optional.of("run-1"),
                        Optional.empty(),
                        ApprovalMode.ASK,
                        Optional.empty(),
                        Optional.empty()),
                new PolicyAction("tool.execute", "invoke"),
                new PolicyResource("tool", "tool-1", Optional.of("sha256:tool"), "Execute tool"),
                new PolicyRisk(
                        PolicyRiskLevel.MEDIUM, Set.of(PolicySideEffect.PROCESS_EXECUTION), false, Optional.empty()));
        return new PolicyDecision(
                new PolicyDecisionId(
                        "decision-" + productId + "-" + effect.name().toLowerCase()),
                Optional.of(request),
                PolicyRequestDigest.compute(request),
                effect,
                effect == PolicyEffect.ASK ? Optional.of(PolicyChallenge.APPROVAL) : Optional.empty(),
                "POLICY_" + effect.name(),
                "Policy decision",
                new PolicySnapshotRef("snapshot-1"),
                Optional.empty(),
                NOW);
    }
}
