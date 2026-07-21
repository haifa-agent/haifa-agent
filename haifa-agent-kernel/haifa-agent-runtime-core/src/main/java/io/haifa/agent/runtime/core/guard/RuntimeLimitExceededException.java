package io.haifa.agent.runtime.core.guard;

public final class RuntimeLimitExceededException extends IllegalStateException {
    public RuntimeLimitExceededException(String message) {
        super(message);
    }
}
