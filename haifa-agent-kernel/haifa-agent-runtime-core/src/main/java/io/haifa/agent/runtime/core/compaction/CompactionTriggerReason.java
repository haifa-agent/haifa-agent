package io.haifa.agent.runtime.core.compaction;

/**
 * Reason that triggered conversation compaction.
 */
public enum CompactionTriggerReason {
    NONE,
    SOFT_TOKEN_THRESHOLD,
    PROVIDER_CONTEXT_TOO_LONG,
    MODEL_DOWNSHIFT,
    SOURCE_REDACTED_REBUILD,
    MANUAL
}
