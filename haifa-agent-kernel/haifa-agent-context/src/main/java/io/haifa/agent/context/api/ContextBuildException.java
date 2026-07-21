package io.haifa.agent.context.api;

public final class ContextBuildException extends RuntimeException {
    private final ContextBuildFailure failure;

    public ContextBuildException(ContextBuildFailure failure, String message) {
        super(message);
        this.failure = java.util.Objects.requireNonNull(failure, "failure must not be null");
    }

    public ContextBuildFailure failure() {
        return failure;
    }
}
