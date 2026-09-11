package io.haifa.agent.runtime.core.loop;

/**
 * Signals that optional context has already been removed but the assembled local window still
 * exceeds the conservative budget.
 *
 * <p>The loop owns the single forced-rebuild policy. This internal signal lets it apply that
 * policy before any model call is made.
 */
final class LocalContextOverflowException extends RuntimeException {
    LocalContextOverflowException() {
        super("assembled context exceeds the local model input budget");
    }
}
