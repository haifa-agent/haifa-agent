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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CodingAgentPolicyAssemblyTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-26T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void mapsCompatibilityModesToLowAndNeverThresholds() {
        assertThat(decide(
                                ApprovalMode.ASK,
                                CodingApprovalThreshold.LOW,
                                PolicyRiskLevel.LOW,
                                Set.of(PolicySideEffect.PROCESS_EXECUTION),
                                false)
                        .effect())
                .isEqualTo(PolicyEffect.ASK);
        assertThat(decide(
                                ApprovalMode.AUTO,
                                CodingApprovalThreshold.NEVER,
                                PolicyRiskLevel.HIGH,
                                Set.of(PolicySideEffect.PROCESS_EXECUTION),
                                false)
                        .effect())
                .isEqualTo(PolicyEffect.ALLOW);
        assertThat(decide(ApprovalMode.DENY, Set.of(PolicySideEffect.PROCESS_EXECUTION), false)
                        .effect())
                .isEqualTo(PolicyEffect.DENY);
    }

    @Test
    void asksAtTheConfiguredMinimumRisk() {
        assertThat(decide(
                                ApprovalMode.ASK,
                                CodingApprovalThreshold.MEDIUM,
                                PolicyRiskLevel.LOW,
                                Set.of(PolicySideEffect.PROCESS_EXECUTION),
                                false)
                        .effect())
                .isEqualTo(PolicyEffect.ALLOW);
        assertThat(decide(
                                ApprovalMode.ASK,
                                CodingApprovalThreshold.MEDIUM,
                                PolicyRiskLevel.MEDIUM,
                                Set.of(PolicySideEffect.FILE_WRITE),
                                false)
                        .effect())
                .isEqualTo(PolicyEffect.ASK);
        assertThat(decide(
                                ApprovalMode.ASK,
                                CodingApprovalThreshold.HIGH,
                                PolicyRiskLevel.MEDIUM,
                                Set.of(PolicySideEffect.NETWORK_ACCESS),
                                false)
                        .effect())
                .isEqualTo(PolicyEffect.ALLOW);
        assertThat(decide(
                                ApprovalMode.ASK,
                                CodingApprovalThreshold.HIGH,
                                PolicyRiskLevel.HIGH,
                                Set.of(PolicySideEffect.EXTERNAL_SYSTEM_MUTATION),
                                false)
                        .reasonCode())
                .isEqualTo("RISK_THRESHOLD_APPROVAL_REQUIRED");
    }

    @Test
    void coversTheCompleteExecutionRiskThresholdDecisionMatrix() {
        var risks = List.of(PolicyRiskLevel.LOW, PolicyRiskLevel.MEDIUM, PolicyRiskLevel.HIGH);
        for (CodingApprovalThreshold threshold : CodingApprovalThreshold.values()) {
            for (PolicyRiskLevel risk : risks) {
                PolicyEffect expected =
                        switch (threshold) {
                            case LOW -> PolicyEffect.ASK;
                            case MEDIUM -> risk == PolicyRiskLevel.LOW ? PolicyEffect.ALLOW : PolicyEffect.ASK;
                            case HIGH -> risk == PolicyRiskLevel.HIGH ? PolicyEffect.ASK : PolicyEffect.ALLOW;
                            case NEVER -> PolicyEffect.ALLOW;
                        };
                assertThat(decide(ApprovalMode.ASK, threshold, risk, Set.of(PolicySideEffect.PROCESS_EXECUTION), false)
                                .effect())
                        .as("threshold=%s risk=%s", threshold, risk)
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    void keepsCriticalCredentialAndPermissionElevationAboveTheOrdinaryThreshold() {

        var credential = decide(ApprovalMode.AUTO, Set.of(PolicySideEffect.CREDENTIAL_USE), true);
        assertThat(credential.effect()).isEqualTo(PolicyEffect.ASK);
        assertThat(credential.challenge()).contains(PolicyChallenge.REAUTHENTICATE);
        var permission = decide(
                ApprovalMode.AUTO,
                CodingApprovalThreshold.NEVER,
                PolicyRiskLevel.HIGH,
                Set.of(PolicySideEffect.PROCESS_EXECUTION, PolicySideEffect.PERMISSION_ELEVATION),
                false);
        assertThat(permission.effect()).isEqualTo(PolicyEffect.ASK);
        assertThat(permission.challenge()).contains(PolicyChallenge.APPROVAL);
        assertThat(permission.reasonCode()).isEqualTo("MANAGED_PERMISSION_ELEVATION_APPROVAL_REQUIRED");
        var credentialWithHighRisk = decide(
                ApprovalMode.AUTO,
                CodingApprovalThreshold.LOW,
                PolicyRiskLevel.HIGH,
                Set.of(PolicySideEffect.PROCESS_EXECUTION, PolicySideEffect.CREDENTIAL_USE),
                true);
        assertThat(credentialWithHighRisk.challenge()).contains(PolicyChallenge.REAUTHENTICATE);
        assertThat(decide(
                                ApprovalMode.AUTO,
                                "execution",
                                PolicyRiskLevel.CRITICAL,
                                Set.of(PolicySideEffect.PROCESS_EXECUTION),
                                false)
                        .effect())
                .isEqualTo(PolicyEffect.DENY);
    }

    @Test
    void keepsTheRiskThresholdScopedToExecutionDispatch() {
        assertThat(decideAction(
                                ApprovalMode.ASK,
                                CodingApprovalThreshold.LOW,
                                PolicyRiskLevel.LOW,
                                "file.read",
                                "invoke",
                                Set.of(),
                                false)
                        .effect())
                .isEqualTo(PolicyEffect.ALLOW);
        assertThat(decideAction(
                                ApprovalMode.ASK,
                                CodingApprovalThreshold.HIGH,
                                PolicyRiskLevel.MEDIUM,
                                "file.write",
                                "invoke",
                                Set.of(PolicySideEffect.FILE_WRITE),
                                false)
                        .effect())
                .isEqualTo(PolicyEffect.ASK);
        assertThat(decideAction(
                                ApprovalMode.AUTO,
                                CodingApprovalThreshold.NEVER,
                                PolicyRiskLevel.MEDIUM,
                                "file.write",
                                "invoke",
                                Set.of(PolicySideEffect.FILE_WRITE),
                                false)
                        .effect())
                .isEqualTo(PolicyEffect.ALLOW);
    }

    private static io.haifa.agent.policy.api.PolicyDecision decide(
            ApprovalMode mode, Set<PolicySideEffect> sideEffects, boolean credentialUse) {
        return decide(mode, "tool", sideEffects, credentialUse);
    }

    private static io.haifa.agent.policy.api.PolicyDecision decide(
            ApprovalMode mode, String resourceType, Set<PolicySideEffect> sideEffects, boolean credentialUse) {
        return decide(mode, resourceType, PolicyRiskLevel.LOW, sideEffects, credentialUse);
    }

    private static io.haifa.agent.policy.api.PolicyDecision decide(
            ApprovalMode mode,
            String resourceType,
            PolicyRiskLevel riskLevel,
            Set<PolicySideEffect> sideEffects,
            boolean credentialUse) {
        return decide(
                mode,
                CodingApprovalThreshold.compatibleWith(mode),
                riskLevel,
                resourceType,
                sideEffects,
                credentialUse);
    }

    private static io.haifa.agent.policy.api.PolicyDecision decide(
            ApprovalMode mode,
            CodingApprovalThreshold threshold,
            PolicyRiskLevel riskLevel,
            Set<PolicySideEffect> sideEffects,
            boolean credentialUse) {
        return decide(mode, threshold, riskLevel, "tool", sideEffects, credentialUse);
    }

    private static io.haifa.agent.policy.api.PolicyDecision decide(
            ApprovalMode mode,
            CodingApprovalThreshold threshold,
            PolicyRiskLevel riskLevel,
            String resourceType,
            Set<PolicySideEffect> sideEffects,
            boolean credentialUse) {
        return decideAction(
                mode, threshold, riskLevel, "execution.run", "invoke", resourceType, sideEffects, credentialUse);
    }

    private static io.haifa.agent.policy.api.PolicyDecision decideAction(
            ApprovalMode mode,
            CodingApprovalThreshold threshold,
            PolicyRiskLevel riskLevel,
            String capability,
            String operation,
            Set<PolicySideEffect> sideEffects,
            boolean credentialUse) {
        return decideAction(mode, threshold, riskLevel, capability, operation, "tool", sideEffects, credentialUse);
    }

    private static io.haifa.agent.policy.api.PolicyDecision decideAction(
            ApprovalMode mode,
            CodingApprovalThreshold threshold,
            PolicyRiskLevel riskLevel,
            String capability,
            String operation,
            String resourceType,
            Set<PolicySideEffect> sideEffects,
            boolean credentialUse) {
        AtomicInteger sequence = new AtomicInteger();
        var assembly = CodingAgentPolicyAssembly.create(
                mode, threshold, CLOCK, () -> "decision-" + sequence.incrementAndGet());
        return assembly.decisions()
                .evaluate(
                        new PolicyRequest(
                                new PolicySubject(
                                        new TenantRef("tenant"),
                                        new PrincipalRef("user", "user"),
                                        "haifa-coding-agent"),
                                PolicyContext.run("run", mode),
                                new PolicyAction(capability, operation),
                                new PolicyResource(resourceType, "test.tool", Optional.of("0".repeat(64)), "Test tool"),
                                new PolicyRisk(riskLevel, sideEffects, credentialUse, Optional.empty())),
                        assembly.snapshot());
    }
}
