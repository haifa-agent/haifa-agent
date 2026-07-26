package io.haifa.agent.sandbox.api;

import java.util.Objects;

public record SandboxPreflight(
        String providerId,
        String adapterId,
        SandboxConfigurationDigest configurationDigest,
        SandboxCapabilities capabilities,
        boolean managedProcessSupported) {
    public SandboxPreflight {
        providerId = identifier(providerId, "providerId");
        adapterId = identifier(adapterId, "adapterId");
        configurationDigest = Objects.requireNonNull(configurationDigest, "configurationDigest must not be null");
        capabilities = Objects.requireNonNull(capabilities, "capabilities must not be null");
    }

    private static String identifier(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (!normalized.matches("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }
}
