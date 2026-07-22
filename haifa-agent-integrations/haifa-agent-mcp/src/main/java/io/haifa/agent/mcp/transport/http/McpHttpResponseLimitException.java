package io.haifa.agent.mcp.transport.http;

public final class McpHttpResponseLimitException extends RuntimeException {
    private final String failureCode;

    public McpHttpResponseLimitException(String failureCode, String message) {
        super(message);
        this.failureCode = failureCode;
    }

    public String failureCode() {
        return failureCode;
    }
}
