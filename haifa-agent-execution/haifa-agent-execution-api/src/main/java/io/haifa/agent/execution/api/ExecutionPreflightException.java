package io.haifa.agent.execution.api;

/** Known failure that prevents an execution from reaching the operating-system process boundary. */
public final class ExecutionPreflightException extends RuntimeException {
    private final String code;

    public ExecutionPreflightException(String code, String safeMessage, Throwable cause) {
        super(safeMessage, cause);
        if (code == null || code.isBlank()) throw new IllegalArgumentException("code must not be blank");
        this.code = code.strip();
    }

    public String code() {
        return code;
    }
}
