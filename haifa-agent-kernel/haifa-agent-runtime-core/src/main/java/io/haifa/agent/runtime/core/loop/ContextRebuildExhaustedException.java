package io.haifa.agent.runtime.core.loop;

/** Typed terminal failure after the single allowed CONTEXT_TOO_LONG rebuild. */
public final class ContextRebuildExhaustedException extends RuntimeException {
    public ContextRebuildExhaustedException(String message) {
        super(message);
    }
}
