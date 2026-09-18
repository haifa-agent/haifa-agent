package io.haifa.agent.store.sqlite.payload;

import io.haifa.agent.core.tool.ToolArguments;
import java.util.Map;

public record ToolArgumentsPayload(String schemaId, String schemaVersion, Map<String, Object> values) {
    public static ToolArgumentsPayload from(ToolArguments value) {
        return new ToolArgumentsPayload(value.schemaId(), value.schemaVersion(), value.values());
    }

    public ToolArguments toDomain() {
        return new ToolArguments(schemaId, schemaVersion, values);
    }
}
