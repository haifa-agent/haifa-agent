package io.haifa.agent.sdk.contribution;

import io.haifa.agent.policy.api.ApprovalVerificationService;
import io.haifa.agent.sdk.product.ProductCapabilities;
import java.util.Objects;

/** Product-selected approval verification boundary. */
public final class ApprovalPlatformContribution extends AbstractSdkContribution {
    private final ApprovalVerificationService verification;

    public ApprovalPlatformContribution(SdkContributionMetadata metadata, ApprovalVerificationService verification) {
        super(metadata);
        if (!ProductCapabilities.APPROVAL.equals(metadata.capabilityId())) {
            throw new IllegalArgumentException("approval contribution must provide the approval capability");
        }
        this.verification = Objects.requireNonNull(verification, "verification must not be null");
    }

    public ApprovalVerificationService verification() {
        return verification;
    }
}
