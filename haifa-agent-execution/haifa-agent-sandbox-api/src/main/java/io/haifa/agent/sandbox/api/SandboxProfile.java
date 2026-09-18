package io.haifa.agent.sandbox.api;

import io.haifa.agent.execution.api.SandboxProfileRef;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Execution configuration snapshot used for audit and hash verification.
 *
 * <p>The profile only carries application-level governance: which executables and environment names are allowed and
 * whether a shell invocation is permitted. Network denial and filesystem mount isolation are not expressible because
 * the platform executes on the host; see {@code docs/34-sandbox-simplification-and-host-execution-design.md}.
 */
public record SandboxProfile(
        SandboxProfileRef ref,
        String providerId,
        SandboxConfigurationDigest providerConfigurationDigest,
        Set<String> allowedExecutables,
        Set<String> allowedEnvironmentNames,
        boolean shellAllowed) {
    public SandboxProfile {
        ref = Objects.requireNonNull(ref, "ref must not be null");
        providerId = identifier(providerId, "providerId");
        providerConfigurationDigest =
                Objects.requireNonNull(providerConfigurationDigest, "providerConfigurationDigest must not be null");
        allowedExecutables =
                Set.copyOf(Objects.requireNonNull(allowedExecutables, "allowedExecutables must not be null"));
        allowedEnvironmentNames =
                Set.copyOf(Objects.requireNonNull(allowedEnvironmentNames, "allowedEnvironmentNames must not be null"));
        if (allowedExecutables.stream().anyMatch(value -> !validName(value))) {
            throw new IllegalArgumentException("allowedExecutables contains an invalid value");
        }
        if (allowedEnvironmentNames.stream().anyMatch(value -> !validEnvironmentName(value))) {
            throw new IllegalArgumentException("allowedEnvironmentNames contains an invalid value");
        }
    }

    public SandboxConfigurationDigest contentDigest() {
        List<String> fields = new ArrayList<>();
        fields.add(ref.value());
        fields.add(ref.version());
        fields.add(providerId);
        fields.add(providerConfigurationDigest.value());
        allowedExecutables.stream().sorted(Comparator.naturalOrder()).forEach(value -> fields.add("exe:" + value));
        allowedEnvironmentNames.stream().sorted(Comparator.naturalOrder()).forEach(value -> fields.add("env:" + value));
        fields.add("shell:" + shellAllowed);
        return SandboxConfigurationDigest.sha256Fields(fields);
    }

    public static SandboxProfile hostGuarded(
            SandboxProfileRef ref,
            SandboxConfigurationDigest providerConfigurationDigest,
            Set<String> allowedExecutables,
            Set<String> allowedEnvironmentNames,
            boolean shellAllowed) {
        return new SandboxProfile(
                ref,
                "host-guarded",
                providerConfigurationDigest,
                allowedExecutables,
                allowedEnvironmentNames,
                shellAllowed);
    }

    private static String identifier(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (!normalized.matches("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static boolean validName(String value) {
        return value != null && !value.isBlank() && value.length() <= 256 && value.indexOf('\0') < 0;
    }

    private static boolean validEnvironmentName(String value) {
        return value != null && value.matches("^[A-Za-z_][A-Za-z0-9_]{0,127}$");
    }
}
