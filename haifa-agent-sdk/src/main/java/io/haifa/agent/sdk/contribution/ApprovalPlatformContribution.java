package io.haifa.agent.sdk.contribution;

import io.haifa.agent.policy.api.ApprovalVerificationService;
import java.util.Objects;

/** Product-selected approval verification boundary. */
public record ApprovalPlatformContribution(ApprovalVerificationService verification) {
    public ApprovalPlatformContribution {
        verification = Objects.requireNonNull(verification, "verification must not be null");
    }
}
