package io.haifa.agent.context.item;

public enum ContextRetention {
    MUST_KEEP,
    KEEP_IF_RELEVANT,
    COMPRESSIBLE,
    REFERENCE_ONLY,
    DROP_FIRST
}
