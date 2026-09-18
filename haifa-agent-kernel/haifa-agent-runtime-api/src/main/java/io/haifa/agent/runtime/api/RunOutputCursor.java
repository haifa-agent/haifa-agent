package io.haifa.agent.runtime.api;

/**
 * Exclusive cursor for the bounded, process-local run-output buffer.
 *
 * <p>This cursor is not durable and is invalid after process restart or active-Run cleanup.
 */
public record RunOutputCursor(long sequence) {
    public static final RunOutputCursor BEFORE_FIRST = new RunOutputCursor(0);

    public RunOutputCursor {
        if (sequence < 0) throw new IllegalArgumentException("output cursor must not be negative");
    }
}
