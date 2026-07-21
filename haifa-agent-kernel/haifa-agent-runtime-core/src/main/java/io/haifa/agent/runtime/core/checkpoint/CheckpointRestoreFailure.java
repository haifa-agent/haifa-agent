package io.haifa.agent.runtime.core.checkpoint;

public enum CheckpointRestoreFailure {
    RUN_OR_OWNER_MISMATCH,
    FROZEN_CONFIGURATION_MISMATCH,
    SESSION_CURSOR_INVALID,
    SUMMARY_INVALID,
    TOOL_STATE_INVALID
}
