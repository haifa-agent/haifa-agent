package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CliExecutionEnvironmentTest {
    @Test
    void windowsInheritsOrdinaryHostToolsAndFiltersSecretsCaseInsensitively() {
        var environment = CliExecutionEnvironment.resolve(
                CliConfiguration.defaults().execution(),
                "host-guarded",
                Map.of(
                        "Path", "C:\\tools;C:\\Windows\\System32",
                        "PATHEXT", ".EXE;.CMD",
                        "SystemRoot", "C:\\Windows",
                        "RG_HOME", "C:\\tools",
                        "ProgramFiles(x86)", "C:\\Program Files (x86)",
                        "DEEPSEEK_API_KEY", "secret"),
                "Windows 11");

        assertThat(environment)
                .containsEntry("Path", "C:\\tools;C:\\Windows\\System32")
                .containsEntry("PATHEXT", ".EXE;.CMD")
                .containsEntry("RG_HOME", "C:\\tools")
                .doesNotContainKeys("DEEPSEEK_API_KEY", "ProgramFiles(x86)");
    }

    @Test
    void windowsSynthesizesCoreCommandResolutionWhenPathIsMissing() {
        var environment = CliExecutionEnvironment.resolve(
                CliConfiguration.defaults().execution(),
                "host-guarded",
                Map.of("SystemRoot", "D:\\Windows"),
                "Windows Server 2025");

        assertThat(environment.get("PATH"))
                .startsWith("D:\\Windows\\System32;")
                .contains("D:\\Windows\\System32\\WindowsPowerShell\\v1.0");
        assertThat(environment).containsEntry("PATHEXT", ".COM;.EXE;.BAT;.CMD");
    }

    @Test
    void linuxAndMacUsePlatformCommandPathFallbacks() {
        var linux = CliExecutionEnvironment.resolve(
                CliConfiguration.defaults().execution(), "host-guarded", Map.of(), "Linux");
        var mac = CliExecutionEnvironment.resolve(
                CliConfiguration.defaults().execution(), "host-guarded", Map.of(), "Mac OS X");

        assertThat(linux)
                .containsEntry("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
                .containsEntry("SHELL", "/bin/sh");
        assertThat(mac)
                .containsEntry("PATH", "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin")
                .containsEntry("SHELL", "/bin/zsh");
    }

    @Test
    void localNativeKeepsToolPathButLeavesHomeAndTempToSandbox() {
        var environment = CliExecutionEnvironment.resolve(
                CliConfiguration.defaults().execution(),
                "local-native",
                Map.of("PATH", "/custom/bin", "HOME", "/host/home", "TMPDIR", "/host/tmp"),
                "Linux");

        assertThat(environment).containsEntry("PATH", "/custom/bin").doesNotContainKeys("HOME", "TMPDIR");
    }
}
