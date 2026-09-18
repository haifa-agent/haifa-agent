package io.haifa.agent.model.api;

/** Provider-neutral reasoning effort. Adapters must reject unsupported values instead of silently downgrading. */
public enum ModelReasoningEffort {
    LOW,
    MEDIUM,
    HIGH,
    MAX
}
