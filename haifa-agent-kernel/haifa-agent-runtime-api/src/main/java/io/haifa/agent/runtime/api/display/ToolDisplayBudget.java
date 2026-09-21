package io.haifa.agent.runtime.api.display;

/**
 * Bounds a display-only preview. These limits only shorten the copied sample; they never change the
 * authoritative Tool Result, the model context projection or any execution limit.
 */
public record ToolDisplayBudget(int maxBytes, int maxLines) {

    /** Approved default byte budget for a bounded tool output preview. */
    public static final int DEFAULT_OUTPUT_MAX_BYTES = 16 * 1_024;

    /** Approved default line budget for a bounded tool output preview. */
    public static final int DEFAULT_OUTPUT_MAX_LINES = 200;

    public ToolDisplayBudget {
        if (maxBytes < 1) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        if (maxLines < 1) {
            throw new IllegalArgumentException("maxLines must be positive");
        }
    }

    /** The approved default display budget for one tool call's bounded output preview. */
    public static ToolDisplayBudget defaultOutput() {
        return new ToolDisplayBudget(DEFAULT_OUTPUT_MAX_BYTES, DEFAULT_OUTPUT_MAX_LINES);
    }
}
