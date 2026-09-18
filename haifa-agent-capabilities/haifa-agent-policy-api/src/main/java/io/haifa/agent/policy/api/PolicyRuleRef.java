package io.haifa.agent.policy.api;

import static io.haifa.agent.policy.api.PolicyValues.requireIdentifier;

public record PolicyRuleRef(String ruleId, String version) {
    public PolicyRuleRef {
        ruleId = requireIdentifier(ruleId, "ruleId");
        version = requireIdentifier(version, "version");
    }
}
