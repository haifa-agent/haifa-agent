package io.haifa.agent.tool.api;

public final class ToolInvocationException extends RuntimeException {
    private final String failureCode;
    private final ToolDispatchState dispatchState;
    private final ToolFailureKind failureKind;

    public ToolInvocationException(String message) {
        this("TOOL_INVOCATION_FAILED", ToolDispatchState.NOT_DISPATCHED, ToolFailureKind.INTERNAL, message, null);
    }

    public ToolInvocationException(String message, Throwable cause) {
        this("TOOL_INVOCATION_FAILED", ToolDispatchState.NOT_DISPATCHED, ToolFailureKind.INTERNAL, message, cause);
    }

    public ToolInvocationException(String failureCode, ToolDispatchState dispatchState, String message) {
        this(failureCode, dispatchState, ToolFailureKind.INTERNAL, message, null);
    }

    public ToolInvocationException(
            String failureCode, ToolDispatchState dispatchState, String message, Throwable cause) {
        this(failureCode, dispatchState, ToolFailureKind.INTERNAL, message, cause);
    }

    public ToolInvocationException(
            String failureCode, ToolDispatchState dispatchState, ToolFailureKind failureKind, String message) {
        this(failureCode, dispatchState, failureKind, message, null);
    }

    public ToolInvocationException(
            String failureCode,
            ToolDispatchState dispatchState,
            ToolFailureKind failureKind,
            String message,
            Throwable cause) {
        super(message, cause);
        if (failureCode == null || failureCode.isBlank()) {
            throw new IllegalArgumentException("failureCode must not be blank");
        }
        this.failureCode = failureCode;
        this.dispatchState = java.util.Objects.requireNonNull(dispatchState, "dispatchState");
        this.failureKind = java.util.Objects.requireNonNull(failureKind, "failureKind");
    }

    public static ToolInvocationException preflight(String failureCode, String message) {
        return new ToolInvocationException(
                failureCode, ToolDispatchState.NOT_DISPATCHED, ToolFailureKind.PREFLIGHT, message, null);
    }

    public static ToolInvocationException preflight(String failureCode, String message, Throwable cause) {
        return new ToolInvocationException(
                failureCode, ToolDispatchState.NOT_DISPATCHED, ToolFailureKind.PREFLIGHT, message, cause);
    }

    public String failureCode() {
        return failureCode;
    }

    public ToolDispatchState dispatchState() {
        return dispatchState;
    }

    public ToolFailureKind failureKind() {
        return failureKind;
    }

    public boolean isPreflight() {
        return failureKind == ToolFailureKind.PREFLIGHT;
    }
}
