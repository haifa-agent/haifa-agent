package io.haifa.agent.model.api;

/** Transient provider health, deliberately separate from configuration lifecycle. */
public enum ProviderHealthStatus {
    UNKNOWN,
    HEALTHY,
    DEGRADED,
    UNAVAILABLE,
    RATE_LIMITED
}
