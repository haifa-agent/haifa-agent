package io.haifa.agent.runtime.core.control;

/** Boundaries at which an executor may cooperatively stop without splitting an operation. */
public enum SafePoint {
    BEFORE_ITERATION,
    AFTER_CONTEXT_BUILD,
    AFTER_MODEL_CALL,
    BEFORE_TOOL_EXECUTION,
    AFTER_TOOL_EXECUTION,
    AFTER_DECISION_PERSISTED,
    AFTER_CHECKPOINT
}
