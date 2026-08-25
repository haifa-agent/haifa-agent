package io.haifa.agent.runtime.core.trace;

/** Stable diagnostic scope for a runtime trace event. */
public enum RuntimeTraceScope {
    RUN,
    ATTEMPT,
    ITERATION,
    STEP,
    TOOL_CALL
}
