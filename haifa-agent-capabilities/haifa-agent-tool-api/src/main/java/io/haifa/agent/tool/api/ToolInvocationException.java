package io.haifa.agent.tool.api;

public final class ToolInvocationException extends RuntimeException {
    public ToolInvocationException(String message) {
        super(message);
    }

    public ToolInvocationException(String message, Throwable cause) {
        super(message, cause);
    }
}
