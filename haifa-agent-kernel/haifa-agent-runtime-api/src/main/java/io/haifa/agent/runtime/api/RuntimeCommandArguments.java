package io.haifa.agent.runtime.api;

import java.util.Map;
import java.util.Objects;

/** Schema-qualified, immutable command arguments. */
public record RuntimeCommandArguments(String schemaId, String schemaVersion, Map<String, Object> values) {
    public static final RuntimeCommandArguments NONE =
            new RuntimeCommandArguments("runtime.command.none", "1.0", Map.of());

    public RuntimeCommandArguments {
        schemaId = requireText(schemaId, "schemaId");
        schemaVersion = requireText(schemaVersion, "schemaVersion");
        values = RuntimeValues.immutableMap(values, "values");
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
