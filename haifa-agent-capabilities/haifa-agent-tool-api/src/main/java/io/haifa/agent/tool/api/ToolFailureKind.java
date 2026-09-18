package io.haifa.agent.tool.api;

/**
 * Categorizes tool invocation failure origins to distinguish expected domain preflight rejections
 * from execution failures and unexpected internal or protocol faults.
 */
public enum ToolFailureKind {
    /**
     * Expected, trusted preflight rejection that occurred before any external dispatch or side effect.
     * Continuable by the agent loop when paired with NOT_DISPATCHED and absent journal dispatch evidence.
     */
    PREFLIGHT,

    /**
     * Failure occurred during or after dispatch, or execution resulted in a failure.
     */
    EXECUTION,

    /**
     * Unexpected internal error, configuration/catalog mismatch, transport breakdown, or unhandled exception.
     * Always fails closed.
     */
    INTERNAL
}
