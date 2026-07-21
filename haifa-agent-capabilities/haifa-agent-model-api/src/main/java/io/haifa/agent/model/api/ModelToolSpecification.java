package io.haifa.agent.model.api;

import java.util.Map;

/** Tool information safe and sufficient to disclose to a chat model. */
public record ModelToolSpecification(
        String name,
        String version,
        String description,
        String inputSchemaId,
        String inputSchemaVersion,
        Map<String, Object> inputJsonSchema,
        boolean strict) {
    public ModelToolSpecification {
        name = ModelValues.text(name, "name");
        version = ModelValues.text(version, "version");
        description = ModelValues.text(description, "description");
        inputSchemaId = ModelValues.text(inputSchemaId, "inputSchemaId");
        inputSchemaVersion = ModelValues.text(inputSchemaVersion, "inputSchemaVersion");
        inputJsonSchema = ModelValues.map(inputJsonSchema, "inputJsonSchema");
        if (inputJsonSchema.isEmpty()) throw new IllegalArgumentException("inputJsonSchema must not be empty");
    }
}
