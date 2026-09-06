package io.haifa.agent.policy.api;

/** Stable public authorization classification; protocol and execution results are intentionally separate. */
public enum AuthorizationClassification {
    HARD_DENY,
    REQUIRES_APPROVAL,
    ALLOW,
    PROTOCOL_ERROR,
    EXECUTION_OUTCOME
}
