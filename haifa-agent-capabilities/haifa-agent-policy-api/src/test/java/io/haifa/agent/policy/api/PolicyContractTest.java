package io.haifa.agent.policy.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class PolicyContractTest {
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
    void legacyDecisionSnapshotAndProjectTrustTypesAreAbsent() {
        assertThat(java.util.Arrays.stream(PolicyContext.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName))
                .doesNotContain("projectTrustRef");
        assertThatThrownBy(() -> Class.forName("io.haifa.agent.policy.api.PolicyDecisionId"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName("io.haifa.agent.policy.api.PolicySnapshot"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName("io.haifa.agent.policy.api.ProjectTrust"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName("io.haifa.agent.policy.api.PolicyRequestDigest"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void safeTextIsBounded() {
        assertThatThrownBy(() -> new PolicyResource("file", "workspace:a", Optional.empty(), "x".repeat(513)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void policyDecisionIsATransientValueWithoutIdentitySnapshotOrPersistenceTime() {
        var accessorNames = java.util.Arrays.stream(PolicyDecision.class.getDeclaredMethods())
                .filter(method -> method.getParameterCount() == 0)
                .map(java.lang.reflect.Method::getName)
                .collect(java.util.stream.Collectors.toSet());

        assertThat(accessorNames)
                .contains("effect", "challenge", "reasonCode", "safeExplanation", "requirementDigest")
                .doesNotContain("id", "request", "requestDigest", "snapshot", "matchedRule", "decidedAt");
    }

    @Test
    void approvalTargetRejectsHostAbsolutePathIdentity() {
        assertThatThrownBy(() -> new ApprovalTargetRef(
                        "workspace", "C:\\secret\\repo", "1", "attach", "sha256:root", "Attach workspace"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host absolute path");
    }

    private static PolicyDecision decision(PolicyEffect effect, Optional<PolicyChallenge> challenge) {
        return new PolicyDecision(effect, challenge, "REASON", "Safe explanation", "sha256:requirement");
    }

    private static ApprovalTargetRef target() {
        return new ApprovalTargetRef(
                "tool-call", "call-1", "1", "write", "sha256:arguments", "Write one workspace file");
    }
}
