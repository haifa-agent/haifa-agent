package io.haifa.agent.testing.suite;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Resolved, integrity-checked Agent Profile input passed to a test JVM. */
public record ResolvedAgentProfile(
        AgentProfileManifest manifest,
        Path configurationPath,
        String agentAssemblyDigest,
        List<String> requiredEnvironmentNames,
        List<String> credentialEnvironmentNames) {
    public ResolvedAgentProfile {
        manifest = Objects.requireNonNull(manifest, "manifest must not be null");
        configurationPath = Objects.requireNonNull(configurationPath, "configurationPath must not be null")
                .toAbsolutePath()
                .normalize();
        agentAssemblyDigest = Objects.requireNonNull(agentAssemblyDigest, "agentAssemblyDigest must not be null");
        if (!agentAssemblyDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("agentAssemblyDigest must be lowercase SHA-256");
        }
        requiredEnvironmentNames = validatedNames(requiredEnvironmentNames, "requiredEnvironmentNames");
        credentialEnvironmentNames = validatedNames(credentialEnvironmentNames, "credentialEnvironmentNames");
        if (!requiredEnvironmentNames.containsAll(credentialEnvironmentNames)) {
            throw new IllegalArgumentException("credentialEnvironmentNames must be required environment names");
        }
    }

    public String profileId() {
        return manifest.profileId();
    }

    private static List<String> validatedNames(List<String> names, String label) {
        List<String> copied = List.copyOf(Objects.requireNonNull(names, label + " must not be null"));
        if (copied.stream().anyMatch(name -> !name.matches("[A-Za-z_][A-Za-z0-9_]*"))) {
            throw new IllegalArgumentException(label + " contains an invalid environment name");
        }
        return copied;
    }
}
