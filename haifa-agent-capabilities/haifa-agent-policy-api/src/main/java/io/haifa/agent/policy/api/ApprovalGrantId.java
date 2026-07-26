package io.haifa.agent.policy.api;

import static io.haifa.agent.policy.api.PolicyValues.requireIdentifier;

public record ApprovalGrantId(String value) {
    public ApprovalGrantId {
        value = requireIdentifier(value, "value");
    }
}
