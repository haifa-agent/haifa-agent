package io.haifa.agent.testing.authorization;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Resolves all secrets required by one execution before external work begins. */
public final class SecretPreflight {
    private SecretPreflight() {}

    public static ResolvedSecrets require(Map<String, String> environment, Collection<String> requiredNames) {
        Objects.requireNonNull(environment, "environment must not be null");
        Objects.requireNonNull(requiredNames, "requiredNames must not be null");
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String name : requiredNames) {
            String normalized = Objects.requireNonNull(name, "required secret name must not be null")
                    .trim();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("required secret name must not be blank");
            }
            names.add(normalized);
        }

        LinkedHashMap<String, String> resolved = new LinkedHashMap<>();
        List<String> missing = names.stream()
                .filter(name -> {
                    String value = environment.get(name);
                    if (value == null || value.isBlank()) return true;
                    resolved.put(name, value);
                    return false;
                })
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("required environment variables are missing: " + missing);
        }
        return new ResolvedSecrets(resolved);
    }

    /** Keeps values available to execution code while ensuring diagnostics only render secret names. */
    public static final class ResolvedSecrets {
        private final Map<String, String> resolved;

        private ResolvedSecrets(Map<String, String> resolved) {
            this.resolved = Collections.unmodifiableMap(new LinkedHashMap<>(resolved));
        }

        public Set<String> names() {
            return resolved.keySet();
        }

        public Collection<String> values() {
            return resolved.values();
        }

        public String value(String name) {
            String value = resolved.get(name);
            if (value == null) throw new IllegalArgumentException("secret was not resolved: " + name);
            return value;
        }

        @Override
        public String toString() {
            return "ResolvedSecrets[names=" + names() + "]";
        }
    }
}
