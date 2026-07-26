package io.haifa.agent.policy.api;

import static io.haifa.agent.policy.api.PolicyValues.requireIdentifier;

public record PolicyAction(String capability, String operation) {
    public PolicyAction {
        capability = requireIdentifier(capability, "capability");
        operation = requireIdentifier(operation, "operation");
    }
}
