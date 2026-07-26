package io.haifa.agent.policy.api;

import static io.haifa.agent.policy.api.PolicyValues.requireIdentifier;

import java.util.Objects;

public record ApprovalAuthorityDecision(ApprovalAuthorityStatus status, String reasonCode) {
    public ApprovalAuthorityDecision {
        status = Objects.requireNonNull(status, "status must not be null");
        reasonCode = requireIdentifier(reasonCode, "reasonCode");
    }

    public boolean eligible() {
        return status == ApprovalAuthorityStatus.ELIGIBLE;
    }
}
