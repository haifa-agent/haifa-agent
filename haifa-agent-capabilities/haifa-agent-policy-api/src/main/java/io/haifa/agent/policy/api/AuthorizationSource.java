package io.haifa.agent.policy.api;

/** Trusted source of a safe authorization projection. */
public enum AuthorizationSource {
    POLICY,
    GRANT,
    PROTOCOL,
    EXECUTION
}
