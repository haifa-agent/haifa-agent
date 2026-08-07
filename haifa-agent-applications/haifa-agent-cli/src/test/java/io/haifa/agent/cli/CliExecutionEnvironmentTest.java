package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliExecutionEnvironmentTest {
    @TempDir
    Path root;

    @Test
    void hostGuardedUsesSharedHostUserPolicyAndFiltersDangerousInterpreterState() throws Exception {
        Path home = Files.createDirectory(root.resolve("home"));
        Path app = Files.createDirectory(root.resolve("app"));
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        Path scratch = Files.createDirectory(root.resolve("scratch"));

        var resolved = CliExecutionEnvironment.resolve(
                CliConfiguration.defaults().execution(),
                "host-guarded",
                Map.of(
                        "HOME",
                        home.toString(),
                        "PATH",
                        "/custom/bin",
                        "DEEPSEEK_API_KEY",
                        "secret",
                        "PYTHONUSERBASE",
                        root.resolve("python-user").toString()),
                "Linux",
                home,
                app,
                workspace,
                scratch);

        assertThat(resolved.environment())
                .containsEntry("HOME", home.toRealPath().toString())
                .containsEntry("PATH", "/custom/bin")
                .doesNotContainKeys("DEEPSEEK_API_KEY", "PYTHONUSERBASE");
    }

    @Test
    void localNativeUsesProviderIsolatedPolicyAndLeavesHomeAndTempToProvider() throws Exception {
        Path home = Files.createDirectory(root.resolve("home"));
        Path app = Files.createDirectory(root.resolve("app"));
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        Path scratch = Files.createDirectory(root.resolve("scratch"));

        var resolved = CliExecutionEnvironment.resolve(
                CliConfiguration.defaults().execution(),
                "local-native",
                Map.of("PATH", "/custom/bin", "HOME", home.toString(), "TMPDIR", scratch.toString()),
                "Linux",
                home,
                app,
                workspace,
                scratch);

        assertThat(resolved.environment())
                .containsEntry("PATH", "/custom/bin")
                .doesNotContainKeys("HOME", "TMPDIR", "USERPROFILE", "APPDATA");
    }
}
