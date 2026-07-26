package io.haifa.agent.sandbox.api;

import io.haifa.agent.execution.api.SandboxProfileRef;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record SandboxProfile(
        SandboxProfileRef ref,
        String providerId,
        SandboxConfigurationDigest providerConfigurationDigest,
        Set<String> allowedExecutables,
        Set<String> allowedEnvironmentNames,
        boolean shellAllowed,
        NetworkPolicy networkPolicy,
        SandboxFilesystemPolicy filesystemPolicy,
        SandboxCapabilities requiredCapabilities) {
    public SandboxProfile {
        ref = Objects.requireNonNull(ref, "ref must not be null");
        providerId = identifier(providerId, "providerId");
        providerConfigurationDigest =
                Objects.requireNonNull(providerConfigurationDigest, "providerConfigurationDigest must not be null");
        allowedExecutables =
                Set.copyOf(Objects.requireNonNull(allowedExecutables, "allowedExecutables must not be null"));
        allowedEnvironmentNames =
                Set.copyOf(Objects.requireNonNull(allowedEnvironmentNames, "allowedEnvironmentNames must not be null"));
        networkPolicy = Objects.requireNonNull(networkPolicy, "networkPolicy must not be null");
        filesystemPolicy = Objects.requireNonNull(filesystemPolicy, "filesystemPolicy must not be null");
        requiredCapabilities = Objects.requireNonNull(requiredCapabilities, "requiredCapabilities must not be null");
        if (allowedExecutables.stream().anyMatch(value -> !validName(value))) {
            throw new IllegalArgumentException("allowedExecutables contains an invalid value");
        }
        if (allowedEnvironmentNames.stream().anyMatch(value -> !validEnvironmentName(value))) {
            throw new IllegalArgumentException("allowedEnvironmentNames contains an invalid value");
        }
        if (!requiredCapabilities.processTreeTermination()) {
            throw new IllegalArgumentException("process-tree termination must be required");
        }
        if (networkPolicy == NetworkPolicy.DENY && !requiredCapabilities.networkIsolation()) {
            throw new IllegalArgumentException("network DENY requires network isolation");
        }
        if (filesystemPolicy.requiresIsolation() && !requiredCapabilities.filesystemMountIsolation()) {
            throw new IllegalArgumentException("filesystem policy requires filesystem isolation");
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
        fields.add("network:" + networkPolicy.name());
        fields.add("workspace:" + filesystemPolicy.workspaceAccess().name());
        fields.add("sensitive:" + filesystemPolicy.sensitivePathsDenied());
        filesystemPolicy.additionalPathPolicyRefs().stream()
                .sorted(Comparator.naturalOrder())
                .forEach(value -> fields.add("path-policy:" + value));
        fields.add("process-tree:" + requiredCapabilities.processTreeTermination());
        fields.add("filesystem:" + requiredCapabilities.filesystemMountIsolation());
        fields.add("network-isolation:" + requiredCapabilities.networkIsolation());
        fields.add("cpu:" + requiredCapabilities.cpuLimit());
        fields.add("memory:" + requiredCapabilities.memoryLimit());
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
                shellAllowed,
                NetworkPolicy.ALLOW,
                SandboxFilesystemPolicy.hostCompatible(),
                new SandboxCapabilities(true, false, false, false, false));
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
