package io.haifa.agent.runtime.api;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Explicit, schema-versioned start-time overrides with a conservative key allowlist. */
public record RuntimeOverrides(String schemaId, String schemaVersion, Map<String, Object> values) {
    public static final Set<String> ALLOWED_KEYS =
            Set.of("temperature", "maxIterations", "maxToolCalls", "maxModelCalls", "maxWallTimeMillis");
    public static final RuntimeOverrides NONE = new RuntimeOverrides("runtime.overrides", "1.0", Map.of());

    public RuntimeOverrides {
        schemaId = requireText(schemaId, "schemaId");
        schemaVersion = requireText(schemaVersion, "schemaVersion");
        values = RuntimeValues.immutableMap(values, "values");
        Set<String> unsupported = new java.util.HashSet<>(values.keySet());
        unsupported.removeAll(ALLOWED_KEYS);
        if (!unsupported.isEmpty()) {
            throw new IllegalArgumentException("unsupported runtime override keys: " + unsupported);
        }
        values.forEach(RuntimeOverrides::validateValue);
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static void validateValue(String key, Object value) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(key + " override must be numeric");
        }
        double numeric = number.doubleValue();
        if (key.equals("temperature")) {
            if (!Double.isFinite(numeric) || numeric < 0 || numeric > 2) {
                throw new IllegalArgumentException("temperature override must be between 0 and 2");
            }
        } else if (numeric < 1 || numeric != Math.rint(numeric)) {
            throw new IllegalArgumentException(key + " override must be a positive integer");
        }
    }
}
