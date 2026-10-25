package io.haifa.agent.runtime.core.storage;

/**
 * Signals that a persistence operation failed, so a step that was interrupted by it must not be reported as a
 * failure of the work it was performing.
 *
 * <p>Runtime Core catches failures by type, not by message: an interpreter adapter or a store translates its own
 * failures into this type (or a subtype), and {@code DefaultAgentLoop} then classifies the Run with
 * {@link io.haifa.agent.core.error.AgentErrorCode#STORAGE_UNAVAILABLE} instead of blaming the model call, tool
 * invocation or other operation the store happened to interrupt.
 */
public class StorageException extends RuntimeException {
    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
