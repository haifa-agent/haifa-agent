package io.haifa.agent.runtime.core.trace;

/** Stable diagnostic status distinct from authoritative Runtime state. */
public enum RuntimeTraceStatus {
    STARTED,
    ACTIVE,
    SUCCESS,
    FAILURE,
    UNKNOWN
}
