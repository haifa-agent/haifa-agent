package io.haifa.agent.runtime.api;

/** Public, transport-neutral model output lifecycle. Private reasoning and tool arguments are intentionally absent. */
public enum AgentRunOutputEventType {
    RUN_OUTPUT_STARTED,
    ASSISTANT_TEXT_DELTA,
    ASSISTANT_TEXT_COMMITTED,
    RUN_OUTPUT_SUPERSEDED,
    RUN_OUTPUT_FAILED
}
