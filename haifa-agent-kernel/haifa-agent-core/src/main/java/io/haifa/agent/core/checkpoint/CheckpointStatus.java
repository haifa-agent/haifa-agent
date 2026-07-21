package io.haifa.agent.core.checkpoint;

/** Validation state recorded with an append-only checkpoint. */
public enum CheckpointStatus {
    CREATED,
    VERIFIED,
    CORRUPTED
}
