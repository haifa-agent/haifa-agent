package io.haifa.agent.mcp.tool;

public record McpToolImportDiagnostic(String code, String message) {
    public McpToolImportDiagnostic {
        if (code == null || code.isBlank() || message == null || message.isBlank()) {
            throw new IllegalArgumentException("diagnostic code and message must not be blank");
        }
    }
}
