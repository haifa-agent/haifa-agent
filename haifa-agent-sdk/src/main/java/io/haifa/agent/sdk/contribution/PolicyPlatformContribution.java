package io.haifa.agent.sdk.contribution;

import io.haifa.agent.policy.api.PolicyDecisionService;
import io.haifa.agent.policy.api.PolicyRuleSet;
import java.util.Objects;

/** Product-owned immutable policy rules plus the shared pure evaluation mechanism. */
public record PolicyPlatformContribution(PolicyRuleSet rules, PolicyDecisionService evaluator) {
    public PolicyPlatformContribution {
        rules = Objects.requireNonNull(rules, "rules must not be null");
        evaluator = Objects.requireNonNull(evaluator, "evaluator must not be null");
    }
}
