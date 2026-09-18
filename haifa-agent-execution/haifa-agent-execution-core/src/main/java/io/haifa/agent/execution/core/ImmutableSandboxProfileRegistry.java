package io.haifa.agent.execution.core;

import io.haifa.agent.execution.api.SandboxProfileRef;
import io.haifa.agent.sandbox.api.SandboxException;
import io.haifa.agent.sandbox.api.SandboxProfile;
import io.haifa.agent.sandbox.api.SandboxResolver;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class ImmutableSandboxProfileRegistry implements SandboxResolver {
    private final Map<SandboxProfileRef, SandboxProfile> profiles;

    public ImmutableSandboxProfileRegistry(Collection<SandboxProfile> profiles) {
        Objects.requireNonNull(profiles, "profiles must not be null");
        Map<SandboxProfileRef, SandboxProfile> indexed = new LinkedHashMap<>();
        for (SandboxProfile profile : profiles) {
            SandboxProfile value = Objects.requireNonNull(profile, "profile must not be null");
            SandboxProfile previous = indexed.putIfAbsent(value.ref(), value);
            if (previous != null) {
                String message = previous.equals(value)
                        ? "duplicate sandbox profile reference"
                        : "sandbox profile reference has conflicting content";
                throw new IllegalArgumentException(message);
            }
        }
        this.profiles = Map.copyOf(indexed);
    }

    @Override
    public SandboxProfile resolve(SandboxProfileRef reference) {
        Objects.requireNonNull(reference, "reference must not be null");
        SandboxProfile profile = profiles.get(reference);
        if (profile == null) {
            throw new SandboxException("CAPABILITY_UNAVAILABLE", "sandbox profile is unavailable");
        }
        return profile;
    }
}
