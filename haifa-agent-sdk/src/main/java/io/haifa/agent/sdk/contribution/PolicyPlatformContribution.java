package io.haifa.agent.sdk.contribution;

import io.haifa.agent.policy.api.PolicyDecisionService;
import io.haifa.agent.policy.api.PolicyRuleSet;
import io.haifa.agent.sdk.product.ProductCapabilities;
import java.util.Objects;

/** Product-owned immutable policy rules plus the shared pure evaluation mechanism. */
public final class PolicyPlatformContribution extends AbstractSdkContribution {
    private final PolicyRuleSet rules;
    private final PolicyDecisionService evaluator;

    public PolicyPlatformContribution(
            SdkContributionMetadata metadata, PolicyRuleSet rules, PolicyDecisionService evaluator) {
        super(metadata);
        if (!ProductCapabilities.POLICY.equals(metadata.capabilityId())) {
            throw new IllegalArgumentException("policy contribution must provide the policy capability");
        }
        this.rules = Objects.requireNonNull(rules, "rules must not be null");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator must not be null");
    }

    public PolicyRuleSet rules() {
        return rules;
    }

    public PolicyDecisionService evaluator() {
        return evaluator;
    }
}
