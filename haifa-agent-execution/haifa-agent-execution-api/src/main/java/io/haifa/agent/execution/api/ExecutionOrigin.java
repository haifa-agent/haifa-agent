package io.haifa.agent.execution.api;

/** Product-declared entry category. Product ExecutionPolicy must reject origins it does not own. */
public enum ExecutionOrigin {
    RUNTIME_TOOL,
    PRODUCT_USER_COMMAND,
    PRODUCT_INTERNAL
}
