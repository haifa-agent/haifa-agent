package io.haifa.agent.policy.api;

import static io.haifa.agent.policy.api.PolicyValues.requireIdentifier;

public record ApprovalVerification(boolean accepted, String reasonCode) {
    public ApprovalVerification {
        reasonCode = requireIdentifier(reasonCode, "reasonCode");
    }
}
