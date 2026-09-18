package io.haifa.agent.policy.api;

import static io.haifa.agent.policy.api.PolicyValues.requireIdentifier;

import java.util.Objects;

public record ApprovalTargetValidation(ApprovalTargetStatus status, String reasonCode) {
    public ApprovalTargetValidation {
        status = Objects.requireNonNull(status, "status must not be null");
        reasonCode = requireIdentifier(reasonCode, "reasonCode");
    }

    public boolean current() {
        return status == ApprovalTargetStatus.CURRENT;
    }
}
