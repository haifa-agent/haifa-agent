package io.haifa.agent.core.run;

import static io.haifa.agent.core.support.DomainValues.immutableMap;
import static io.haifa.agent.core.support.DomainValues.requireText;

import java.util.Map;

/** Provider-neutral, immutable JSON Schema contract for a Run's terminal structured output. */
public record StructuredOutputRequirement(
        String schemaId, String schemaVersion, String responseName, Map<String, Object> jsonSchema) {

    public StructuredOutputRequirement {
        schemaId = requireText(schemaId, "schemaId");
        schemaVersion = requireText(schemaVersion, "schemaVersion");
        responseName = requireText(responseName, "responseName");
        if (responseName.length() > 64 || !responseName.matches("[A-Za-z][A-Za-z0-9_-]*")) {
            throw new IllegalArgumentException(
                    "responseName must be 1-64 ASCII letters, digits, underscores, or hyphens and start with a letter");
        }
        jsonSchema = immutableMap(jsonSchema, "jsonSchema");
        if (jsonSchema.isEmpty()) throw new IllegalArgumentException("jsonSchema must not be empty");
    }
}
