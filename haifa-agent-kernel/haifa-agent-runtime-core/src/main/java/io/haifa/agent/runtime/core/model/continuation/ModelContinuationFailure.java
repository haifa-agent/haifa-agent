package io.haifa.agent.runtime.core.model.continuation;

public enum ModelContinuationFailure {
    MISSING,
    CORRUPT,
    BINDING_MISMATCH,
    UNAUTHORIZED,
    VERSION_UNSUPPORTED,
    CROSS_MODEL_UNCLOSED_TOOL_GROUP
}
