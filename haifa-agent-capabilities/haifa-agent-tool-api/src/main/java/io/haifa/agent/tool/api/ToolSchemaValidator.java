package io.haifa.agent.tool.api;

import java.util.Map;

public interface ToolSchemaValidator {
    ToolSchemaValidationResult validate(ToolSchema schema, Map<String, Object> instance);
}
