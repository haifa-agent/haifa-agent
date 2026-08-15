package io.haifa.agent.model.api;

import java.util.Objects;

/** Stable fail-closed error from common profile validation and parameter resolution. */
public final class ModelParameterResolutionException extends IllegalArgumentException {
    private final ModelParameterResolutionFailure failure;

    public ModelParameterResolutionException(ModelParameterResolutionFailure failure, String message) {
        super(message);
        this.failure = Objects.requireNonNull(failure, "failure must not be null");
    }

    public ModelParameterResolutionFailure failure() {
        return failure;
    }
}
