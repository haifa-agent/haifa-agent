package io.haifa.agent.context.trace;

public enum ContextSelectionDecision {
    SELECTED,
    DROPPED_SECURITY,
    DROPPED_DUPLICATE,
    DROPPED_BUDGET
}
