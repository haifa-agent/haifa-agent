package io.haifa.agent.policy.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.policy.api.ApprovalMode;
import io.haifa.agent.policy.api.PolicyAction;
import io.haifa.agent.policy.api.PolicyContext;
import io.haifa.agent.policy.api.PolicyRequest;
import io.haifa.agent.policy.api.PolicyRequirementDigest;
import io.haifa.agent.policy.api.PolicyResource;
import io.haifa.agent.policy.api.PolicyRisk;
import io.haifa.agent.policy.api.PolicyRiskLevel;
import io.haifa.agent.policy.api.PolicyRuleSet;
import io.haifa.agent.policy.api.PolicySideEffect;
import io.haifa.agent.policy.api.PolicySubject;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PolicyRequirementDigestTest {
    private static final PolicyRuleSet RULES = PolicyRuleSet.of(List.of(), Optional.empty(), ApprovalMode.ASK);

    @Test
    void excludesAttemptPrincipalAndRunIdentityFromApprovalRequirement() {
        PolicyRequest baseline = request("local", "run-a", "attempt-a");
        PolicyRequest changed = request("other-principal", "run-b", "attempt-b");

        assertThat(PolicyRequirementDigest.compute(baseline, RULES))
                .isEqualTo(PolicyRequirementDigest.compute(changed, RULES));
    }

    @Test
    void includesFrozenRuleMatchTargetSecurityAndRiskFacts() {
        String baseline = PolicyRequirementDigest.compute(request("local", "run", "attempt"), RULES);
        PolicyRequest changedTarget = new PolicyRequest(
                new PolicySubject(new TenantRef("tenant"), new PrincipalRef("user", "local"), "coding"),
                context("run", "attempt"),
                new PolicyAction("workspace.file", "write"),
                new PolicyResource("file", "workspace:OTHER", Optional.of("sha256:other"), "Write other"),
                new PolicyRisk(
                        PolicyRiskLevel.CRITICAL, Set.of(PolicySideEffect.FILE_WRITE), true, Optional.of("host")));

        assertThat(PolicyRequirementDigest.compute(changedTarget, RULES)).isNotEqualTo(baseline);
        assertThat(baseline).startsWith("sha256:");
    }

    private static PolicyRequest request(String principalId, String runRef, String attemptRef) {
        return new PolicyRequest(
                new PolicySubject(new TenantRef("tenant"), new PrincipalRef("user", principalId), "coding"),
                context(runRef, attemptRef),
                new PolicyAction("workspace.file", "write"),
                new PolicyResource("file", "workspace:README.md", Optional.of("sha256:resource"), "Write README"),
                new PolicyRisk(PolicyRiskLevel.HIGH, Set.of(PolicySideEffect.FILE_WRITE), false, Optional.empty()));
    }

    private static PolicyContext context(String runRef, String attemptRef) {
        return new PolicyContext(
                Optional.of("project"),
                Optional.of("session"),
                Optional.of(runRef),
                Optional.of(attemptRef),
                ApprovalMode.ASK,
                Optional.of("sha256:config"));
    }
}
