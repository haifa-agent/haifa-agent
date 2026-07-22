package io.haifa.agent.runtime.core.tool;

public enum ToolJournalState {
    INTENT_RECORDED,
    DISPATCHED,
    ACKNOWLEDGED,
    PENDING_RESULT,
    COMPLETED,
    FAILED,
    OUTCOME_UNKNOWN
}
