package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.execution.api.ExecutionOutputChannel;
import io.haifa.agent.execution.api.ProcessOutputChunk;
import io.haifa.agent.project.core.store.InMemoryWorkspaceBindingStore;
import io.haifa.agent.project.core.store.InMemoryWorkspaceStore;
import io.haifa.agent.project.hostworkspace.HostWorkspaceLocationStore;
import io.haifa.agent.sandbox.host.HostGuardedSandboxProvider;
import io.haifa.agent.sandbox.host.HostShell;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CliExecutionPlatformTest {
    @Test
    void defaultsFreezeTrustedHostExecutionIndependentOfHostOperatingSystem() {
        CliConfiguration.Execution configuration = CliConfiguration.defaults().execution();
        HostGuardedSandboxProvider provider = hostProvider();

        var profile = CliExecutionPlatform.profile(configuration, provider);
        var preflight = provider.preflight(profile);

        assertThat(configuration.provider()).isEqualTo("host-guarded");
        assertThat(profile.providerId()).isEqualTo("host-guarded");
        assertThat(preflight.capabilities().processTreeTermination()).isTrue();
        assertThat(preflight.managedProcessSupported()).isTrue();
        assertThat(profile.allowedExecutables()).containsExactly("git");
        assertThat(configuration.inheritEnvironment()).containsExactly("*");
        assertThat(profile.allowedEnvironmentNames()).anyMatch(name -> name.equalsIgnoreCase("PATH"));
        assertThat(CliExecutionPlatform.securitySummary(profile, preflight))
                .contains(
                        "provider=host-guarded (controlled host execution, trusted local development)",
                        "network=host",
                        "host loopback/LAN/internet may be reachable",
                        "current OS user",
                        "workspace/outside files/network/CPU/memory/kernel are not isolated",
                        "approval is not isolation",
                        "profile=")
                .doesNotContain("fallback", "explicit trusted compatibility");
    }

    @Test
    void rejectsAnyProviderOtherThanTheSingleHostImplementation() {
        CliConfiguration.Execution defaults = CliConfiguration.defaults().execution();

        assertThatThrownBy(() -> new CliConfiguration.Execution(
                        "local-native",
                        defaults.shell(),
                        defaults.shellPath(),
                        defaults.defaultTimeout(),
                        defaults.maximumTimeout(),
                        defaults.maxOutputBytes(),
                        defaults.maxOutputLines(),
                        defaults.maxProcesses(),
                        defaults.inheritEnvironment()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("execution.provider is unsupported");
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

    private static HostGuardedSandboxProvider hostProvider() {
        return new HostGuardedSandboxProvider(
                new InMemoryWorkspaceStore(),
                new InMemoryWorkspaceBindingStore(),
                new HostWorkspaceLocationStore(),
                () -> "session",
                () -> Instant.parse("2026-07-26T00:00:00Z"),
                HostShell.auto());
    }
}
