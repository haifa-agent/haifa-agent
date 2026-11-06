package io.haifa.agent.runtime.core.context;

/** Internal markers shared by context selection, projection, compaction and persisted continuation metadata. */
public final class ContextProjectionMetadata {
    public static final String UNIFIED = "unified-context";
    public static final String DATA = "working-set-data";
    public static final String IN_USE = "working-set-in-use";
    public static final String PHASE_LABEL_PREFIX = "context-phase:";
    public static final String PHASE_METADATA_KEY = "contextProjectionPhase";
    public static final String COMPLETE_SOURCE = "complete-source-v1";
    public static final String PLAN_SOURCE = "working-set-plan";
    public static final String SUMMARY_HANDOFF_SOURCE = "working-set-summary";
    public static final String CURRENT_RUN_SUMMARY = "working-set-current-run-summary";

    private ContextProjectionMetadata() {}
}
