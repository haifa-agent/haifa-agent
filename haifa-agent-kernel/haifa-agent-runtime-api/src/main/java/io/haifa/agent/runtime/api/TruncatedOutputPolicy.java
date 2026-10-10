package io.haifa.agent.runtime.api;

/**
 * Governs agent runtime handling when model generation terminates with finish reason LENGTH.
 */
public enum TruncatedOutputPolicy {
    /** Fail closed (default): ordinary text truncation is a terminal execution error; legacy Tool handling is unchanged. */
    FAIL_CLOSED,

    /** Opt-in: accept ordinary non-blank truncated plain text as a capped final answer. */
    ACCEPT_NONEMPTY_PLAIN_TEXT
}
