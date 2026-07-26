package io.haifa.agent.policy.api;

import static io.haifa.agent.policy.api.PolicyValues.requireIdentifier;

public record PolicyDecisionId(String value) {
    public PolicyDecisionId {
        value = requireIdentifier(value, "value");
    }
}
