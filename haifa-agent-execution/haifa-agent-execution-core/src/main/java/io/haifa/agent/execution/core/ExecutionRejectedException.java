package io.haifa.agent.execution.core;

public final class ExecutionRejectedException extends RuntimeException {
    private final String code;

    public ExecutionRejectedException(String code, String safeMessage) {
        super(safeMessage);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
