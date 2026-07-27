package io.haifa.agent.execution.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.execution.api.SandboxProfileRef;
import io.haifa.agent.sandbox.api.NetworkPolicy;
import io.haifa.agent.sandbox.api.SandboxCapabilities;
import io.haifa.agent.sandbox.api.SandboxException;
import io.haifa.agent.sandbox.api.SandboxFilesystemPolicy;
import io.haifa.agent.sandbox.api.SandboxProfile;
import io.haifa.agent.sandbox.api.SandboxProvider;
import io.haifa.agent.sandbox.api.SandboxSession;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SandboxRegistryTest {
    @Test
    void resolvesExactImmutableProfileAndProvider() {
        SandboxProvider provider = provider("local-native");
        SandboxProfile profile = profile(provider, "profile-1");
        var profiles = new ImmutableSandboxProfileRegistry(List.of(profile));
        var providers = new ImmutableSandboxProviderRegistry(List.of(provider));

        assertThat(profiles.resolve(profile.ref())).isSameAs(profile);
        assertThat(providers.resolve(profile)).isSameAs(provider);
    }

    @Test
    void rejectsDuplicateAndUnknownBindings() {
        SandboxProvider first = provider("local-native");
        SandboxProvider second = provider("local-native");
        assertThatThrownBy(() -> new ImmutableSandboxProviderRegistry(List.of(first, second)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");

        SandboxProfile profile = profile(first, "profile-1");
        SandboxProfile conflict = new SandboxProfile(
                profile.ref(),
                profile.providerId(),
                profile.providerConfigurationDigest(),
                Set.of("git"),
                profile.allowedEnvironmentNames(),
                profile.shellAllowed(),
                profile.networkPolicy(),
                profile.filesystemPolicy(),
                profile.requiredCapabilities());
        assertThatThrownBy(() -> new ImmutableSandboxProfileRegistry(List.of(profile, conflict)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conflicting");

        var emptyProfiles = new ImmutableSandboxProfileRegistry(List.of());
        assertThatThrownBy(() -> emptyProfiles.resolve(new SandboxProfileRef("missing", "1")))
                .isInstanceOfSatisfying(SandboxException.class, exception -> assertThat(exception.code())
                        .isEqualTo("CAPABILITY_UNAVAILABLE"));

        var emptyProviders = new ImmutableSandboxProviderRegistry(List.of());
        assertThatThrownBy(() -> emptyProviders.resolve(profile))
                .isInstanceOfSatisfying(SandboxException.class, exception -> assertThat(exception.code())
                        .isEqualTo("SANDBOX_ADAPTER_UNAVAILABLE"));
    }

    private static SandboxProfile profile(SandboxProvider provider, String value) {
        return new SandboxProfile(
                new SandboxProfileRef(value, "1"),
                provider.providerId(),
                provider.configurationDigest(),
                Set.of("java"),
                Set.of(),
                false,
                NetworkPolicy.ALLOW,
                SandboxFilesystemPolicy.hostCompatible(),
                new SandboxCapabilities(true, false, false, false, false));
    }

    private static SandboxProvider provider(String id) {
        return new SandboxProvider() {
            @Override
            public String providerId() {
                return id;
            }

            @Override
            public SandboxCapabilities capabilities() {
                return new SandboxCapabilities(true, false, false, false, false);
            }

            @Override
            public SandboxSession open(SandboxProfile profile, io.haifa.agent.sandbox.api.WorkspaceMount mount) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
