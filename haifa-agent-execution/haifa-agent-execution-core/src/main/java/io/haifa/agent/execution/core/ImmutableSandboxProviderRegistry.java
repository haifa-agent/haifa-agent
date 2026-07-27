package io.haifa.agent.execution.core;

import io.haifa.agent.sandbox.api.SandboxException;
import io.haifa.agent.sandbox.api.SandboxProfile;
import io.haifa.agent.sandbox.api.SandboxProvider;
import io.haifa.agent.sandbox.api.SandboxProviderResolver;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class ImmutableSandboxProviderRegistry implements SandboxProviderResolver {
    private final Map<String, SandboxProvider> providers;

    public ImmutableSandboxProviderRegistry(Collection<SandboxProvider> providers) {
        Objects.requireNonNull(providers, "providers must not be null");
        Map<String, SandboxProvider> indexed = new LinkedHashMap<>();
        for (SandboxProvider provider : providers) {
            SandboxProvider value = Objects.requireNonNull(provider, "provider must not be null");
            String providerId = Objects.requireNonNull(value.providerId(), "providerId must not be null")
                    .trim();
            if (!providerId.matches("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")) {
                throw new IllegalArgumentException("providerId is invalid");
            }
            if (indexed.putIfAbsent(providerId, value) != null) {
                throw new IllegalArgumentException("duplicate sandbox provider id");
            }
        }
        this.providers = Map.copyOf(indexed);
    }

    @Override
    public SandboxProvider resolve(SandboxProfile profile) {
        Objects.requireNonNull(profile, "profile must not be null");
        SandboxProvider provider = providers.get(profile.providerId());
        if (provider == null) {
            throw new SandboxException("SANDBOX_ADAPTER_UNAVAILABLE", "sandbox provider is unavailable");
        }
        return provider;
    }
}
