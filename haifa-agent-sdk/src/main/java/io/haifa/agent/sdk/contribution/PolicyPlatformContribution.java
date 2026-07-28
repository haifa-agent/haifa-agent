package io.haifa.agent.sdk.contribution;

import io.haifa.agent.policy.api.PolicyAuthorizationEvidenceStore;
import io.haifa.agent.policy.api.PolicyDecisionStore;
import io.haifa.agent.sdk.product.ProductCapabilities;
import java.util.Objects;

/** Product-selected durable Policy decision and authorization-evidence stores. */
public final class PolicyPlatformContribution extends AbstractSdkContribution {
    private final PolicyDecisionStore decisions;
    private final PolicyAuthorizationEvidenceStore authorizationEvidence;

    public PolicyPlatformContribution(
            SdkContributionMetadata metadata,
            PolicyDecisionStore decisions,
            PolicyAuthorizationEvidenceStore authorizationEvidence) {
        super(metadata);
        if (!ProductCapabilities.POLICY.equals(metadata.capabilityId())) {
            throw new IllegalArgumentException("policy contribution must provide the policy capability");
        }
        this.decisions = Objects.requireNonNull(decisions, "decisions must not be null");
        this.authorizationEvidence =
                Objects.requireNonNull(authorizationEvidence, "authorizationEvidence must not be null");
    }

    public PolicyDecisionStore decisions() {
        return decisions;
    }

    public PolicyAuthorizationEvidenceStore authorizationEvidence() {
        return authorizationEvidence;
    }
}
