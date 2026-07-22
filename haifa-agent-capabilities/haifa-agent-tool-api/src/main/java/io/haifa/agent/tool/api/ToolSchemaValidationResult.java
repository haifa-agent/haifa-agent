package io.haifa.agent.tool.api;

import java.util.List;

public record ToolSchemaValidationResult(List<ToolSchemaValidationError> errors) {
    public ToolSchemaValidationResult {
        errors = List.copyOf(errors);
    }

    public boolean valid() {
        return errors.isEmpty();
    }
}
