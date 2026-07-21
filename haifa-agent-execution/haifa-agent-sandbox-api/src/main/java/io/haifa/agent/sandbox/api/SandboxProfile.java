package io.haifa.agent.sandbox.api;

import io.haifa.agent.execution.api.SandboxProfileRef;
import java.util.Objects;
import java.util.Set;

public record SandboxProfile(
        SandboxProfileRef ref,
        Set<String> allowedExecutables,
        Set<String> allowedEnvironmentNames,
        boolean shellAllowed,
        NetworkPolicy networkPolicy) {
    public SandboxProfile {
        ref = Objects.requireNonNull(ref, "ref must not be null");
        allowedExecutables =
                Set.copyOf(Objects.requireNonNull(allowedExecutables, "allowedExecutables must not be null"));
        allowedEnvironmentNames =
                Set.copyOf(Objects.requireNonNull(allowedEnvironmentNames, "allowedEnvironmentNames must not be null"));
        networkPolicy = Objects.requireNonNull(networkPolicy, "networkPolicy must not be null");
    }
}
