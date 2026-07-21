package io.haifa.agent.runtime.core.checkpoint;

import java.util.Objects;

public final class CheckpointRestoreException extends RuntimeException {
    private final CheckpointRestoreFailure failure;

    public CheckpointRestoreException(CheckpointRestoreFailure failure, String message) {
        super(message);
        this.failure = Objects.requireNonNull(failure, "failure must not be null");
    }

    public CheckpointRestoreException(CheckpointRestoreFailure failure, String message, Throwable cause) {
        super(message, cause);
        this.failure = Objects.requireNonNull(failure, "failure must not be null");
    }

    public CheckpointRestoreFailure failure() {
        return failure;
    }
}
