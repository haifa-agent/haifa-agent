package io.haifa.agent.runtime.core.tool;

/** @deprecated Use the public PolicyEffect and PolicyChallenge decision model. */
@Deprecated(forRemoval = true)
public enum ToolPolicyDecision {
    ALLOW,
    REQUIRE_APPROVAL,
    REQUIRE_REAUTHENTICATION,
    DENY
}
