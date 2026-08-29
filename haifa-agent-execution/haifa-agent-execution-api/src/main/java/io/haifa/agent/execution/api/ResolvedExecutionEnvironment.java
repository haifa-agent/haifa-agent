package io.haifa.agent.execution.api;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Resolved execution environment containing all environment variable mappings and the set of
 * variable names whose values originate from sensitive leases (and thus must be redacted).
 */
public record ResolvedExecutionEnvironment(Map<String, String> values, Set<String> sensitiveNames) {

    public ResolvedExecutionEnvironment {
        Objects.requireNonNull(values, "values must not be null");
        Objects.requireNonNull(sensitiveNames, "sensitiveNames must not be null");
        values = Map.copyOf(values);
        sensitiveNames = Set.copyOf(sensitiveNames);
        for (String name : sensitiveNames) {
            if (!values.containsKey(name)) {
                throw new IllegalArgumentException(
                        "sensitive environment variable '%s' is not present in values".formatted(name));
            }
        }
    }

    public static ResolvedExecutionEnvironment of(Map<String, String> values) {
        return new ResolvedExecutionEnvironment(values, Set.of());
    }

    public static ResolvedExecutionEnvironment of(Map<String, String> values, Set<String> sensitiveNames) {
        return new ResolvedExecutionEnvironment(values, sensitiveNames);
    }
}
