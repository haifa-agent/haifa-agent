package io.haifa.agent.sdk.tool;

import io.haifa.agent.sdk.internal.JavaRecordSupport;
import io.haifa.agent.tool.api.ToolSchema;
import java.util.Objects;

/** Generates the bounded JSON Schema subset supported by JavaTool record inputs and outputs. */
public final class JavaRecordSchemaGenerator {
    public ToolSchema generate(String schemaId, String schemaVersion, Class<? extends Record> recordType) {
        return new ToolSchema(
                Objects.requireNonNull(schemaId, "schemaId must not be null"),
                Objects.requireNonNull(schemaVersion, "schemaVersion must not be null"),
                JavaRecordSupport.schema(Objects.requireNonNull(recordType, "recordType must not be null")));
    }
}
