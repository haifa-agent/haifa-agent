package io.haifa.agent.tool.api;

/** Recovery-oriented effect classification; authorization continues to use ToolRisk and ToolSideEffect. */
public enum ToolEffectClass {
    PURE_READ,
    IDEMPOTENT,
    SIDE_EFFECTING
}
