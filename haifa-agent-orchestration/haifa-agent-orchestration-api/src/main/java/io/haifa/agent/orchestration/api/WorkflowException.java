package io.haifa.agent.orchestration.api;

import java.util.Objects;

public final class WorkflowException extends RuntimeException {
    private final WorkflowErrorCode code;
    private final String operation;

    public WorkflowException(WorkflowErrorCode code, String operation, String message) {
        this(code, operation, message, null);
    }

    public WorkflowException(WorkflowErrorCode code, String operation, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code must not be null");
        this.operation = Objects.requireNonNull(operation, "operation must not be null");
    }

    public WorkflowErrorCode code() {
        return code;
    }

    public String operation() {
        return operation;
    }
}
