package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.execution.api.ExecutionOutputChannel;
import io.haifa.agent.execution.api.ProcessOutputChunk;
import io.haifa.agent.project.hostworkspace.HostWorkspaceLocationStore;
import io.haifa.agent.project.store.InMemoryWorkspaceBindingStore;
import io.haifa.agent.project.store.InMemoryWorkspaceStore;
import io.haifa.agent.sandbox.api.NetworkPolicy;
import io.haifa.agent.sandbox.api.SandboxException;
import io.haifa.agent.sandbox.host.HostGuardedSandboxProvider;
import io.haifa.agent.sandbox.host.HostShell;
import io.haifa.agent.sandbox.localnative.LocalNativeSandboxProvider;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class CliExecutionPlatformTest {
    @Test
    void defaultsFreezeTrustedHostAllowIndependentOfHostOperatingSystem() {
        CliConfiguration.Execution configuration = CliConfiguration.defaults().execution();
        HostGuardedSandboxProvider provider = hostProvider(configuration);

        var profile = CliExecutionPlatform.profile(configuration, provider);
        var preflight = provider.preflight(profile);

        assertThat(configuration.provider()).isEqualTo("host-guarded");
        assertThat(configuration.network()).isEqualTo("allow");
        assertThat(profile.providerId()).isEqualTo("host-guarded");
        assertThat(profile.networkPolicy()).isEqualTo(NetworkPolicy.ALLOW);
        assertThat(profile.requiredCapabilities().networkIsolation()).isFalse();
        assertThat(preflight.managedProcessSupported()).isTrue();
        assertThat(configuration.inheritEnvironment()).containsExactly("*");
        assertThat(profile.allowedEnvironmentNames()).anyMatch(name -> name.equalsIgnoreCase("PATH"));
        assertThat(CliExecutionPlatform.securitySummary(profile, preflight))
                .contains(
                        "provider=host-guarded (trusted local development)",
                        "network=ALLOW",
                        "host loopback/LAN/internet may be reachable",
                        "current OS user",
                        "workspace/outside files/network/CPU/memory/kernel are not strongly isolated",
                        "approval is not isolation",
                        "profile=")
                .doesNotContain("fallback", "explicit trusted compatibility");
    }

    @Test
    void windowsLocalNativeDiagnosticRequiresExplicitTrustedHostChoiceWithoutFallback() {
        Assumptions.assumeTrue(isWindows());
        CliConfiguration.Execution configuration = localNativeStrictConfiguration();
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
    void explicitLocalNativeStrictProfileDiffersFromDefaultHostWithoutFallback() {
        CliConfiguration.Execution defaults = CliConfiguration.defaults().execution();
        CliConfiguration.Execution localConfiguration = localNativeStrictConfiguration();
        HostGuardedSandboxProvider host = hostProvider(defaults);

        var hostProfile = CliExecutionPlatform.profile(defaults, host);
        var localProfile = CliExecutionPlatform.profile(localConfiguration, localProvider(localConfiguration));

        assertThat(hostProfile.providerId()).isEqualTo("host-guarded");
        assertThat(hostProfile.networkPolicy()).isEqualTo(NetworkPolicy.ALLOW);
        assertThat(host.preflight(hostProfile).managedProcessSupported()).isTrue();
        assertThat(localProfile.providerId()).isEqualTo("local-native");
        assertThat(localProfile.networkPolicy()).isEqualTo(NetworkPolicy.DENY);
        assertThat(localProfile.requiredCapabilities().networkIsolation()).isTrue();
        assertThat(hostProfile.contentDigest()).isNotEqualTo(localProfile.contentDigest());
    }

    @Test
    void streamedOutputKeepsDynamicWorkspacePathWhileRemovingUnsafeControlText() throws Exception {
        Path workspace = Path.of(System.getProperty("java.io.tmpdir"), "haifa 空格", "workspace")
                .toAbsolutePath();
        String line = workspace + "/src/Main.java\n";
        var bytes = new ByteArrayOutputStream();
        var observer = new CliExecutionPlatform.CliOutputObserver(new PrintStream(bytes, true, StandardCharsets.UTF_8));

        observer.onOutput(new ProcessOutputChunk(
                ExecutionOutputChannel.STDOUT,
                ("\u001B[31m" + line.substring(0, line.length() / 2)).getBytes(StandardCharsets.UTF_8),
                false,
                false));
        observer.onOutput(new ProcessOutputChunk(
                ExecutionOutputChannel.STDOUT,
                (line.substring(line.length() / 2) + "\u0000").getBytes(StandardCharsets.UTF_8),
                true,
                false));

        assertThat(bytes.toString(StandardCharsets.UTF_8))
                .isEqualTo(line)
                .contains(workspace.toString())
                .doesNotContain("<workspace>", "\u001B", "\u0000");
    }

    @Test
    void normalizesAbsoluteWorkdirsOnlyInsideTheAuthorizedWorkspace() {
        Path workspace = Path.of(System.getProperty("java.io.tmpdir"), "haifa-workdir", "workspace")
                .toAbsolutePath()
                .normalize();
        var normalizer = CliExecutionPlatform.workspaceWorkdirNormalizer(workspace);

        assertThat(normalizer.apply(workspace.toString())).isEqualTo(".");
        assertThat(normalizer.apply(workspace.resolve("src").resolve("main").toString()))
                .isEqualTo("src/main");
        assertThat(normalizer.apply("src/test")).isEqualTo("src/test");

        String outside = workspace.resolveSibling("outside").toString();
        assertThat(normalizer.apply(outside)).isEqualTo(outside);
    }

    private static HostGuardedSandboxProvider hostProvider(CliConfiguration.Execution configuration) {
        return new HostGuardedSandboxProvider(
                new InMemoryWorkspaceStore(),
                new InMemoryWorkspaceBindingStore(),
                new HostWorkspaceLocationStore(),
                () -> "session",
                () -> Instant.parse("2026-07-26T00:00:00Z"),
                HostShell.auto());
    }

    private static CliConfiguration.Execution localNativeStrictConfiguration() {
        CliConfiguration.Execution defaults = CliConfiguration.defaults().execution();
        return new CliConfiguration.Execution(
                "local-native",
                "deny",
                defaults.shell(),
                defaults.shellPath(),
                defaults.defaultTimeout(),
                defaults.maximumTimeout(),
                defaults.maxOutputBytes(),
                defaults.maxOutputLines(),
                defaults.maxProcesses(),
                defaults.inheritEnvironment(),
                List.of());
    }

    private static LocalNativeSandboxProvider localProvider(CliConfiguration.Execution configuration) {
        HostShell shell = HostShell.auto();
        return new LocalNativeSandboxProvider(
                new InMemoryWorkspaceStore(),
                new InMemoryWorkspaceBindingStore(),
                new HostWorkspaceLocationStore(),
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
