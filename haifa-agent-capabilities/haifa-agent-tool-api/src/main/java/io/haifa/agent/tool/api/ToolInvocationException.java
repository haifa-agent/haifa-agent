package io.haifa.agent.tool.api;

public final class ToolInvocationException extends RuntimeException {
    private final String failureCode;
    private final ToolDispatchState dispatchState;

    public ToolInvocationException(String message) {
        this("TOOL_INVOCATION_FAILED", ToolDispatchState.NOT_DISPATCHED, message, null);
    }

    public ToolInvocationException(String message, Throwable cause) {
        this("TOOL_INVOCATION_FAILED", ToolDispatchState.NOT_DISPATCHED, message, cause);
    }

    public ToolInvocationException(String failureCode, ToolDispatchState dispatchState, String message) {
        this(failureCode, dispatchState, message, null);
    }

    public ToolInvocationException(
            String failureCode, ToolDispatchState dispatchState, String message, Throwable cause) {
        super(message, cause);
        if (failureCode == null || failureCode.isBlank()) {
            throw new IllegalArgumentException("failureCode must not be blank");
        }
        this.failureCode = failureCode;
        this.dispatchState = java.util.Objects.requireNonNull(dispatchState, "dispatchState");
    }

    public String failureCode() {
        return failureCode;
    }

    public ToolDispatchState dispatchState() {
        return dispatchState;
    }
}
