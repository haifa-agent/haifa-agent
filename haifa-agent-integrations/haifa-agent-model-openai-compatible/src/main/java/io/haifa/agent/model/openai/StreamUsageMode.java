package io.haifa.agent.model.openai;

/** Defines how an OpenAI-compatible SSE stream reports token usage. */
public enum StreamUsageMode {
    /** A single final usage snapshot is expected; a different later snapshot is a protocol error. */
    FINAL_ONLY_STRICT,

    /** Usage chunks are cumulative snapshots and only the final monotonic value is published. */
    MONOTONIC_CUMULATIVE
}
