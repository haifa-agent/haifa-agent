package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.project.provider.local.LocalWorkspaceLocationStore;
import io.haifa.agent.project.store.InMemoryWorkspaceBindingStore;
import io.haifa.agent.project.store.InMemoryWorkspaceStore;
import io.haifa.agent.sandbox.api.NetworkPolicy;
import io.haifa.agent.sandbox.api.SandboxException;
import io.haifa.agent.sandbox.host.HostGuardedSandboxProvider;
import io.haifa.agent.sandbox.host.HostShell;
import io.haifa.agent.sandbox.localnative.LocalNativeSandboxProvider;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class CliExecutionPlatformTest {
    @Test
    void defaultsFreezeLocalNativeDenyIndependentOfHostOperatingSystem() {
        CliConfiguration.Execution configuration = CliConfiguration.defaults().execution();
        LocalNativeSandboxProvider provider = localProvider(configuration);

        var profile = CliExecutionPlatform.profile(configuration, provider);

        assertThat(configuration.provider()).isEqualTo("local-native");
        assertThat(configuration.network()).isEqualTo("deny");
        assertThat(profile.providerId()).isEqualTo("local-native");
        assertThat(profile.networkPolicy()).isEqualTo(NetworkPolicy.DENY);
        assertThat(profile.requiredCapabilities().networkIsolation()).isTrue();
    }

    @Test
    void windowsLocalNativeDiagnosticRequiresExplicitTrustedHostChoiceWithoutFallback() {
        Assumptions.assumeTrue(isWindows());
        CliConfiguration.Execution configuration = CliConfiguration.defaults().execution();
        LocalNativeSandboxProvider provider = localProvider(configuration);
        var profile = CliExecutionPlatform.profile(configuration, provider);

        assertThatThrownBy(() -> {
                    try {
                        provider.preflight(profile);
                    } catch (SandboxException exception) {
                        throw CliExecutionPlatform.diagnostic(configuration, exception);
                    }
                })
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SANDBOX_ADAPTER_UNAVAILABLE")
                .hasMessageContaining("explicitly trusted workspace")
                .hasMessageContaining("host-guarded");
        assertThat(profile.providerId()).isEqualTo("local-native");
    }

    @Test
    void explicitHostCompatibilityUsesAllowAndDoesNotMutateLocalProfile() {
        CliConfiguration.Execution defaults = CliConfiguration.defaults().execution();
        CliConfiguration.Execution hostConfiguration = new CliConfiguration.Execution(
                "host-guarded",
                "allow",
                defaults.shell(),
                defaults.shellPath(),
                defaults.defaultTimeout(),
                defaults.maximumTimeout(),
                defaults.maxOutputBytes(),
                defaults.maxOutputLines(),
                defaults.maxProcesses(),
                defaults.inheritEnvironment(),
                List.of());
        HostShell shell = HostShell.auto();
        HostGuardedSandboxProvider host = new HostGuardedSandboxProvider(
                new InMemoryWorkspaceStore(),
                new InMemoryWorkspaceBindingStore(),
                new LocalWorkspaceLocationStore(),
                () -> "session",
                () -> Instant.parse("2026-07-26T00:00:00Z"),
                shell);

        var hostProfile = CliExecutionPlatform.profile(hostConfiguration, host);
        var localProfile = CliExecutionPlatform.profile(defaults, localProvider(defaults));

        assertThat(hostProfile.providerId()).isEqualTo("host-guarded");
        assertThat(hostProfile.networkPolicy()).isEqualTo(NetworkPolicy.ALLOW);
        assertThat(host.preflight(hostProfile).managedProcessSupported()).isTrue();
        assertThat(localProfile.providerId()).isEqualTo("local-native");
        assertThat(hostProfile.contentDigest()).isNotEqualTo(localProfile.contentDigest());
    }

    private static LocalNativeSandboxProvider localProvider(CliConfiguration.Execution configuration) {
        HostShell shell = HostShell.auto();
        return new LocalNativeSandboxProvider(
                new InMemoryWorkspaceStore(),
                new InMemoryWorkspaceBindingStore(),
                new LocalWorkspaceLocationStore(),
                () -> "session",
                () -> Instant.parse("2026-07-26T00:00:00Z"),
                CliExecutionPlatform.localConfiguration(configuration, shell));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win");
    }
}
