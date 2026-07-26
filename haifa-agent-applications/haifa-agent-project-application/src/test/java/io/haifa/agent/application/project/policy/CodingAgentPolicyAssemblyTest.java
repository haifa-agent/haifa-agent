package io.haifa.agent.application.project.policy;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.policy.api.ApprovalMode;
import io.haifa.agent.policy.api.PolicyAction;
import io.haifa.agent.policy.api.PolicyChallenge;
import io.haifa.agent.policy.api.PolicyContext;
import io.haifa.agent.policy.api.PolicyEffect;
import io.haifa.agent.policy.api.PolicyRequest;
import io.haifa.agent.policy.api.PolicyResource;
import io.haifa.agent.policy.api.PolicyRisk;
import io.haifa.agent.policy.api.PolicyRiskLevel;
import io.haifa.agent.policy.api.PolicySideEffect;
import io.haifa.agent.policy.api.PolicySubject;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CodingAgentPolicyAssemblyTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-26T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void mapsCodingApprovalModesWithoutWeakeningCredentialUse() {
        assertThat(decide(ApprovalMode.ASK, Set.of(PolicySideEffect.PROCESS_EXECUTION), false)
                        .effect())
                .isEqualTo(PolicyEffect.ASK);
        assertThat(decide(ApprovalMode.AUTO, Set.of(PolicySideEffect.PROCESS_EXECUTION), false)
                        .effect())
                .isEqualTo(PolicyEffect.ALLOW);
        assertThat(decide(ApprovalMode.DENY, Set.of(PolicySideEffect.PROCESS_EXECUTION), false)
                        .effect())
                .isEqualTo(PolicyEffect.DENY);

        var credential = decide(ApprovalMode.AUTO, Set.of(PolicySideEffect.CREDENTIAL_USE), true);
        assertThat(credential.effect()).isEqualTo(PolicyEffect.ASK);
        assertThat(credential.challenge()).contains(PolicyChallenge.REAUTHENTICATE);
        assertThat(decide(ApprovalMode.ASK, Set.of(), false).effect()).isEqualTo(PolicyEffect.ALLOW);
    }

    private static io.haifa.agent.policy.api.PolicyDecision decide(
            ApprovalMode mode, Set<PolicySideEffect> sideEffects, boolean credentialUse) {
        AtomicInteger sequence = new AtomicInteger();
        var assembly = CodingAgentPolicyAssembly.create(mode, CLOCK, () -> "decision-" + sequence.incrementAndGet());
        return assembly.decisions()
                .evaluate(
                        new PolicyRequest(
                                new PolicySubject(
                                        new TenantRef("tenant"),
                                        new PrincipalRef("user", "user"),
                                        "haifa-coding-agent"),
                                PolicyContext.run("run", mode),
                                new PolicyAction("test.tool", "invoke"),
                                new PolicyResource("tool", "test.tool", Optional.of("0".repeat(64)), "Test tool"),
                                new PolicyRisk(PolicyRiskLevel.LOW, sideEffects, credentialUse, Optional.empty())),
                        assembly.snapshot());
    }
}
