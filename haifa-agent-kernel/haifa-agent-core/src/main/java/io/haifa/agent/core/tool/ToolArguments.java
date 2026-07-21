package io.haifa.agent.core.tool;

import static io.haifa.agent.core.support.DomainValues.immutableMap;
import static io.haifa.agent.core.support.DomainValues.requireText;

import java.util.Map;

/** Validated and normalized tool arguments under a declared schema. */
public record ToolArguments(String schemaId, String schemaVersion, Map<String, Object> values) {
    public ToolArguments {
        schemaId = requireText(schemaId, "schemaId");
        schemaVersion = requireText(schemaVersion, "schemaVersion");
        values = immutableMap(values, "values");
    }
}
