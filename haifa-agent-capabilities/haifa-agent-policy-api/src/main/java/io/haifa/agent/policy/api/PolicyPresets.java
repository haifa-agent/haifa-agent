package io.haifa.agent.policy.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Standard product-neutral policy presets.
 *
 * <p>A preset is ordinary immutable {@link PolicyRuleSet} data, not a policy owner: products select a
 * preset explicitly (the SDK Starter picks {@link #standardApproval()}, the complete SDK and the
 * product applications supply their own rules) and the shared pure evaluation mechanism stays in
 * {@code haifa-agent-policy-core}. No assembly layer constructs rules on a caller's behalf.
 */
public final class PolicyPresets {
    private PolicyPresets() {}

    /**
     * Safe default preset for a trusted-host Agent: critical risk is denied, the side-effecting
     * capabilities require approval, and every other operation is allowed with approval available on
     * demand through {@link ApprovalMode#ASK}.
     */
    public static PolicyRuleSet standardApproval() {
        List<PolicyRule> rules = new ArrayList<>();
        rules.add(new PolicyRule(
                new PolicyRuleRef("preset-critical-risk", "1"),
                PolicyRuleSource.MANAGED,
                200,
                new PolicyRuleMatcher(
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(PolicyRiskLevel.CRITICAL),
                        Set.of()),
                PolicyEffect.DENY,
                Optional.empty(),
                "PRESET_CRITICAL_RISK_DENY",
                "Critical operations are denied"));
        for (PolicySideEffect effect : List.of(
                PolicySideEffect.FILE_WRITE,
                PolicySideEffect.PROCESS_EXECUTION,
                PolicySideEffect.NETWORK_ACCESS,
                PolicySideEffect.EXTERNAL_SYSTEM_MUTATION,
                PolicySideEffect.PERMISSION_ELEVATION)) {
            rules.add(new PolicyRule(
                    new PolicyRuleRef("preset-ask-" + effect.name().toLowerCase(Locale.ROOT), "1"),
                    PolicyRuleSource.MANAGED,
                    100,
                    new PolicyRuleMatcher(
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Set.of(effect)),
                    PolicyEffect.ASK,
                    Optional.of(PolicyChallenge.APPROVAL),
                    "PRESET_SIDE_EFFECT_APPROVAL_REQUIRED",
                    "Approval is required"));
        }
        PolicyRule defaultRule = new PolicyRule(
                new PolicyRuleRef("preset-default", "1"),
                PolicyRuleSource.MANAGED,
                0,
                PolicyRuleMatcher.any(),
                PolicyEffect.ALLOW,
                Optional.empty(),
                "PRESET_DEFAULT_ALLOW",
                "Allowed by the standard policy preset");
        return PolicyRuleSet.of(rules, Optional.of(defaultRule), ApprovalMode.ASK);
    }
}
