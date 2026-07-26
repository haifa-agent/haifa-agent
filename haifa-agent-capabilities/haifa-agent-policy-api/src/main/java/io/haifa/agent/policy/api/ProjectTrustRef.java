package io.haifa.agent.policy.api;

import static io.haifa.agent.policy.api.PolicyValues.requireIdentifier;

public record ProjectTrustRef(String value) {
    public ProjectTrustRef {
        value = requireIdentifier(value, "value");
    }
}
