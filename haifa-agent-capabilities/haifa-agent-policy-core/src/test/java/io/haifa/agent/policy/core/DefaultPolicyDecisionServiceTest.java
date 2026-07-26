package io.haifa.agent.policy.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.policy.api.ApprovalMode;
import io.haifa.agent.policy.api.PolicyAction;
import io.haifa.agent.policy.api.PolicyChallenge;
import io.haifa.agent.policy.api.PolicyContext;
import io.haifa.agent.policy.api.PolicyDecision;
import io.haifa.agent.policy.api.PolicyDecisionId;
import io.haifa.agent.policy.api.PolicyEffect;
import io.haifa.agent.policy.api.PolicyRequest;
import io.haifa.agent.policy.api.PolicyResource;
import io.haifa.agent.policy.api.PolicyRisk;
import io.haifa.agent.policy.api.PolicyRiskLevel;
import io.haifa.agent.policy.api.PolicyRule;
import io.haifa.agent.policy.api.PolicyRuleMatcher;
import io.haifa.agent.policy.api.PolicyRuleRef;
import io.haifa.agent.policy.api.PolicyRuleSource;
import io.haifa.agent.policy.api.PolicySideEffect;
import io.haifa.agent.policy.api.PolicySnapshot;
import io.haifa.agent.policy.api.PolicySnapshotRef;
import io.haifa.agent.policy.api.PolicySubject;
import io.haifa.agent.policy.api.ProjectTrustRef;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefaultPolicyDecisionServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-26T00:00:00Z");
    private static final DefaultPolicyDecisionService SERVICE =
            new DefaultPolicyDecisionService(Clock.fixed(NOW, ZoneOffset.UTC), () -> new PolicyDecisionId("decision"));

    @Test
    void denyWinsOverAskAndAllowIndependentOfRegistrationOrder() {
        PolicyRule allow = rule("allow", PolicyRuleSource.USER, PolicyEffect.ALLOW, Optional.empty(), 100);
        PolicyRule ask =
                rule("ask", PolicyRuleSource.MANAGED, PolicyEffect.ASK, Optional.of(PolicyChallenge.APPROVAL), 10);
        PolicyRule deny = rule("deny", PolicyRuleSource.SYSTEM, PolicyEffect.DENY, Optional.empty(), 0);

        PolicyDecision first = SERVICE.evaluate(request(Optional.empty()), snapshot(List.of(allow, ask, deny)));
        PolicyDecision second = SERVICE.evaluate(request(Optional.empty()), snapshot(List.of(deny, allow, ask)));

        assertThat(first.effect()).isEqualTo(PolicyEffect.DENY);
        assertThat(first.matchedRule()).contains(deny.ref());
        assertThat(second).isEqualTo(first);
    }

    @Test
    void askWinsOverAllowAndCarriesChallenge() {
        PolicyDecision decision = SERVICE.evaluate(
                request(Optional.empty()),
                snapshot(List.of(
                        rule("allow", PolicyRuleSource.USER, PolicyEffect.ALLOW, Optional.empty(), 10),
                        rule(
                                "ask",
                                PolicyRuleSource.MANAGED,
                                PolicyEffect.ASK,
                                Optional.of(PolicyChallenge.REAUTHENTICATE),
                                0))));

        assertThat(decision.effect()).isEqualTo(PolicyEffect.ASK);
        assertThat(decision.challenge()).contains(PolicyChallenge.REAUTHENTICATE);
    }

    @Test
    void missingRuleAndDefaultFailsClosed() {
        PolicyDecision decision = SERVICE.evaluate(request(Optional.empty()), snapshot(List.of()));

        assertThat(decision.effect()).isEqualTo(PolicyEffect.DENY);
        assertThat(decision.reasonCode()).isEqualTo("POLICY_NO_MATCH");
        assertThat(decision.matchedRule()).isEmpty();
    }

    @Test
    void untrustedProjectAllowDoesNotGrantCapability() {
        PolicyRule projectAllow =
                rule("project-allow", PolicyRuleSource.PROJECT, PolicyEffect.ALLOW, Optional.empty(), 0);

        assertThat(SERVICE.evaluate(request(Optional.empty()), snapshot(List.of(projectAllow)))
                        .effect())
                .isEqualTo(PolicyEffect.DENY);
        assertThat(SERVICE.evaluate(request(Optional.of(new ProjectTrustRef("trust"))), snapshot(List.of(projectAllow)))
                        .effect())
                .isEqualTo(PolicyEffect.ALLOW);
    }

    @Test
    void fixedMatchersAreAppliedWithoutScriptOrMapInputs() {
        PolicyRuleMatcher matcher = new PolicyRuleMatcher(
                Optional.of("tenant"),
                Optional.of("coding"),
                Optional.of("project"),
                Optional.of("session"),
                Optional.of("workspace.file"),
                Optional.of("write"),
                Optional.of("file"),
                Optional.of(PolicyRiskLevel.HIGH),
                Set.of(PolicySideEffect.FILE_WRITE));
        PolicyRule matched = new PolicyRule(
                new PolicyRuleRef("matched", "1"),
                PolicyRuleSource.MANAGED,
                0,
                matcher,
                PolicyEffect.ASK,
                Optional.of(PolicyChallenge.APPROVAL),
                "FILE_WRITE_CONFIRM",
                "Confirm a workspace write");

        assertThat(SERVICE.evaluate(request(Optional.empty()), snapshot(List.of(matched)))
                        .effect())
                .isEqualTo(PolicyEffect.ASK);
    }

    private static PolicyRequest request(Optional<ProjectTrustRef> trust) {
        return new PolicyRequest(
                new PolicySubject(new TenantRef("tenant"), new PrincipalRef("user", "local"), "coding"),
                new PolicyContext(
                        Optional.of("project"),
                        Optional.of("session"),
                        Optional.of("run"),
                        Optional.of("attempt"),
                        ApprovalMode.ASK,
                        trust,
                        Optional.of("sha256:config")),
                new PolicyAction("workspace.file", "write"),
                new PolicyResource("file", "workspace:README.md", Optional.of("sha256:resource"), "Write README"),
                new PolicyRisk(PolicyRiskLevel.HIGH, Set.of(PolicySideEffect.FILE_WRITE), false, Optional.empty()));
    }

    private static PolicyRule rule(
            String id,
            PolicyRuleSource source,
            PolicyEffect effect,
            Optional<PolicyChallenge> challenge,
            int priority) {
        return new PolicyRule(
                new PolicyRuleRef(id, "1"),
                source,
                priority,
                PolicyRuleMatcher.any(),
                effect,
                challenge,
                id.toUpperCase(),
                "Safe " + id);
    }

    private static PolicySnapshot snapshot(List<PolicyRule> rules) {
        return new PolicySnapshot(
                new PolicySnapshotRef("snapshot"),
                rules,
                Optional.empty(),
                ApprovalMode.ASK,
                "coding",
                Optional.empty(),
                "sha256:snapshot",
                NOW);
    }
}
