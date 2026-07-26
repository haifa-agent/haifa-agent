package io.haifa.agent.policy.api;

import static io.haifa.agent.policy.api.PolicyValues.requireIdentifier;

public record PolicySnapshotRef(String value) {
    public PolicySnapshotRef {
        value = requireIdentifier(value, "value");
    }
}
