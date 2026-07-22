package io.haifa.agent.tool.api;

public record ToolSchemaValidationError(String path, String keyword, String message) {
    public ToolSchemaValidationError {
        path = ToolValues.text(path, "path");
        keyword = ToolValues.text(keyword, "keyword");
        message = ToolValues.text(message, "message");
    }
}
