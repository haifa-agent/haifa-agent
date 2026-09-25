package io.haifa.agent.runtime.core.loop;

/** Redacted counters and timings for one Runtime context build. */
record ContextPreparationMetrics(
        long contextBuildElapsedMillis,
        long sessionSelectionElapsedMillis,
        long historyRowsRead,
        long activeRowsSelected,
        long atomicGroupCandidateScans,
        long atomicGroupsBuilt,
        long toolCallBatchCount,
        long continuationBatchCount,
        long continuationRecordCount,
        long summaryRenderCacheHits,
        long summaryRenderCacheMisses,
        long snapshotHits,
        String snapshotRebuildReason,
        long snapshotDeltaRows,
        long snapshotEstimatedTokens,
        long snapshotCharacters,
        long snapshotPayloadBytes) {
    static final String MIDDLEWARE_ATTRIBUTE = "runtime.context-preparation-metrics";
    static final ContextPreparationMetrics NONE =
            new ContextPreparationMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "NONE", 0, 0, 0, 0);

    public ContextPreparationMetrics {
        if (contextBuildElapsedMillis < 0
                || sessionSelectionElapsedMillis < 0
                || historyRowsRead < 0
                || activeRowsSelected < 0
                || atomicGroupCandidateScans < 0
                || atomicGroupsBuilt < 0
                || toolCallBatchCount < 0
                || continuationBatchCount < 0
                || continuationRecordCount < 0
                || summaryRenderCacheHits < 0
                || summaryRenderCacheMisses < 0
                || snapshotHits < 0
                || snapshotDeltaRows < 0
                || snapshotEstimatedTokens < 0
                || snapshotCharacters < 0
                || snapshotPayloadBytes < 0) {
            throw new IllegalArgumentException("context preparation metrics must not be negative");
        }
        if (snapshotRebuildReason == null || snapshotRebuildReason.isBlank()) {
            throw new IllegalArgumentException("snapshotRebuildReason must not be blank");
        }
    }

    static ContextPreparationMetrics from(
            long contextBuildElapsedMillis, SessionMessageSource.SelectionMetrics selection) {
        return new ContextPreparationMetrics(
                contextBuildElapsedMillis,
                selection.elapsedMillis(),
                selection.historyRowsRead(),
                selection.activeRowsSelected(),
                selection.atomicGroupCandidateScans(),
                selection.atomicGroupsBuilt(),
                selection.toolCallBatchCount(),
                selection.continuationBatchCount(),
                selection.continuationRecordCount(),
                selection.summaryRenderCacheHits(),
                selection.summaryRenderCacheMisses(),
                selection.snapshotHits(),
                selection.snapshotRebuildReason(),
                selection.snapshotDeltaRows(),
                selection.snapshotEstimatedTokens(),
                selection.snapshotCharacters(),
                selection.snapshotPayloadBytes());
    }
}
