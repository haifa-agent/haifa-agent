package io.haifa.agent.runtime.core.tool;

/** Raised only when the authoritative bounded Tool Result cannot be persisted. */
final class ToolResultPersistenceException extends RuntimeException {
    ToolResultPersistenceException(RuntimeException cause) {
        super("authoritative tool result persistence failed", cause);
    }
}
