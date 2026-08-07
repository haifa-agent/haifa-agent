package io.haifa.agent.sandbox.host;

import java.util.Map;
import java.util.Set;

/** Immutable result of resolving a trusted execution environment at a product assembly boundary. */
public record ResolvedHostEnvironment(
        Map<String, String> environment, Set<String> allowedEnvironmentNames, String diagnosticCode) {
    public ResolvedHostEnvironment {
        environment = Map.copyOf(environment);
        allowedEnvironmentNames = Set.copyOf(allowedEnvironmentNames);
        if (!environment.keySet().equals(allowedEnvironmentNames)) {
            throw new IllegalArgumentException("environment names must match the resolved environment");
        }
        if (diagnosticCode == null || diagnosticCode.isBlank()) {
            throw new IllegalArgumentException("diagnosticCode must not be blank");
        }
    }
}
