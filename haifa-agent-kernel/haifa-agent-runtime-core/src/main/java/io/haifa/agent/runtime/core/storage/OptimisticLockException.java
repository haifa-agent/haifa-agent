package io.haifa.agent.runtime.core.storage;

public final class OptimisticLockException extends RuntimeException {
    public OptimisticLockException(String message) {
        super(message);
    }
}
