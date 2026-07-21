package io.haifa.agent.model.core;

import java.util.Objects;

/** Safe pre-invocation model selection failure. */
public final class ModelSelectionException extends RuntimeException {
    private final ModelSelectionFailure failure;

    public ModelSelectionException(ModelSelectionFailure failure, String message) {
        super(message);
        this.failure = Objects.requireNonNull(failure, "failure must not be null");
    }

    public ModelSelectionFailure failure() {
        return failure;
    }
}
