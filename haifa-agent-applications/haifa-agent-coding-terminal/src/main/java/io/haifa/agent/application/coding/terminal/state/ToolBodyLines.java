package io.haifa.agent.application.coding.terminal.state;

/**
 * Single source for the structural line prefixes and markers the Tool transcript body uses. The
 * reducer that writes the body and the collapsed preview filter that skips it share these constants so
 * a format change cannot silently turn a structural line into preview content.
 */
public final class ToolBodyLines {
    public static final String TARGET_PREFIX = "Target:";
    public static final String OUTCOME_PREFIX = "Outcome:";
    public static final String STATUS_PREFIX = "Status:";
    public static final String REASON_PREFIX = "Reason:";
    public static final String NEXT_PREFIX = "Next:";
    public static final String RESULT_PREFIX = "Result:";
    public static final String OUTPUT = "Output:";
    public static final String OUTPUT_TRUNCATED = "Output (truncated):";
    public static final String OUTPUT_STREAMING = "Output (streaming):";
    public static final String OUTPUT_TRUNCATED_SUMMARY_PREFIX = "Output truncated · ";
    public static final String STDOUT = "[stdout]";
    public static final String STDERR = "[stderr]";
    public static final String EXECUTION_OUTPUT_TRUNCATED = "[execution output truncated]";
    public static final String PREVIEW_OUTPUT_DROPPED = "[preview output dropped]";

    private ToolBodyLines() {}

    /** Whether a Tool body line is structural metadata rather than readable result content. */
    public static boolean isStructural(String line) {
        return line.startsWith(TARGET_PREFIX)
                || line.startsWith(OUTCOME_PREFIX)
                || line.startsWith(STATUS_PREFIX)
                || line.startsWith(REASON_PREFIX)
                || line.startsWith(NEXT_PREFIX)
                || line.startsWith(RESULT_PREFIX)
                || line.startsWith(OUTPUT_TRUNCATED_SUMMARY_PREFIX)
                || line.equals(OUTPUT)
                || line.equals(OUTPUT_TRUNCATED)
                || line.equals(OUTPUT_STREAMING)
                || line.equals(STDOUT)
                || line.equals(STDERR)
                || line.equals(EXECUTION_OUTPUT_TRUNCATED)
                || line.equals(PREVIEW_OUTPUT_DROPPED);
    }
}
