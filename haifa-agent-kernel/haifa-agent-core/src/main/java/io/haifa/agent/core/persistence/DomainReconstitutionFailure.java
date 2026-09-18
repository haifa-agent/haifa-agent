package io.haifa.agent.core.persistence;

/** Stable failure categories for persistence adapters and recovery diagnostics. */
public enum DomainReconstitutionFailure {
    UNSUPPORTED_SCHEMA_VERSION,
    UNKNOWN_ENUM,
    INVALID_HISTORY
}
