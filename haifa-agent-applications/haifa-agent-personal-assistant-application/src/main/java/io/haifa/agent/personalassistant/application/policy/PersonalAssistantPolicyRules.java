package io.haifa.agent.personalassistant.application.policy;

import io.haifa.agent.policy.api.ApprovalMode;
import io.haifa.agent.policy.api.PolicyChallenge;
import io.haifa.agent.policy.api.PolicyEffect;
import io.haifa.agent.policy.api.PolicyRule;
import io.haifa.agent.policy.api.PolicyRuleMatcher;
import io.haifa.agent.policy.api.PolicyRuleRef;
import io.haifa.agent.policy.api.PolicyRuleSet;
import io.haifa.agent.policy.api.PolicyRuleSource;
import io.haifa.agent.policy.api.PolicySideEffect;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Immutable Action Policy rules owned by the Personal Assistant product. */
public final class PersonalAssistantPolicyRules {
    private static final String PRODUCT_ID = "haifa-personal-assistant";

    private PersonalAssistantPolicyRules() {}

    public static PolicyRuleSet conservative() {
        List<PolicyRule> rules = new ArrayList<>();
        for (PolicySideEffect effect : List.of(
                PolicySideEffect.FILE_WRITE,
                PolicySideEffect.PROCESS_EXECUTION,
                PolicySideEffect.NETWORK_ACCESS,
                PolicySideEffect.EXTERNAL_SYSTEM_MUTATION,
                PolicySideEffect.PERMISSION_ELEVATION,
                PolicySideEffect.CREDENTIAL_USE)) {
            rules.add(new PolicyRule(
                    new PolicyRuleRef("personal-ask-" + effect.name().toLowerCase(java.util.Locale.ROOT), "1"),
                    PolicyRuleSource.MANAGED,
                    100,
                    new PolicyRuleMatcher(
                            Optional.empty(),
                            Optional.of(PRODUCT_ID),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Set.of(effect)),
                    PolicyEffect.ASK,
                    Optional.of(
                            effect == PolicySideEffect.CREDENTIAL_USE
                                    ? PolicyChallenge.REAUTHENTICATE
                                    : PolicyChallenge.APPROVAL),
                    "PERSONAL_SIDE_EFFECT_APPROVAL_REQUIRED",
                    "Personal Assistant requires approval for this side effect"));
        }
        PolicyRule allowRead = new PolicyRule(
                new PolicyRuleRef("personal-default-read", "1"),
                PolicyRuleSource.MANAGED,
                0,
                PolicyRuleMatcher.any(),
                PolicyEffect.ALLOW,
                Optional.empty(),
                "PERSONAL_READ_ALLOWED",
                "Personal Assistant read operation is allowed");
        return PolicyRuleSet.of(rules, Optional.of(allowRead), ApprovalMode.ASK);
    }
}
