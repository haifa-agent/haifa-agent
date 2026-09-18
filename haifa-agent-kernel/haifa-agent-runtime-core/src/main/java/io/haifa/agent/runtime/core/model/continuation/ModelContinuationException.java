package io.haifa.agent.runtime.core.model.continuation;

public final class ModelContinuationException extends RuntimeException {
    private final ModelContinuationFailure failure;

    public ModelContinuationException(ModelContinuationFailure failure, String message) {
        super(message);
        this.failure = failure;
    }

    public ModelContinuationException(ModelContinuationFailure failure, String message, Throwable cause) {
        super(message, cause);
        this.failure = failure;
    }

    public ModelContinuationFailure failure() {
        return failure;
    }
}
