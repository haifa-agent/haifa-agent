package io.haifa.agent.runtime.api;

/** Exclusive cursor for reconnectable run-output reads. */
public record RunOutputCursor(long sequence) {
    public static final RunOutputCursor BEFORE_FIRST = new RunOutputCursor(0);

    public RunOutputCursor {
        if (sequence < 0) throw new IllegalArgumentException("output cursor must not be negative");
    }
}
