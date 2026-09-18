package io.haifa.agent.execution.core;

/** Broker-owned invocation position; it is never part of a persisted execution request. */
public enum ExecutionPolicyEntryPoint {
    FIRST_EXECUTION,
    IDEMPOTENT_REPLAY,
    MANAGED_SESSION
}
