package io.haifa.agent.sandbox.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class HostShellTest {
    @Test
    void compilesTrustedBashAndPowerShellLaunchArgumentsWithoutParsingCommandText() {
        Path executable = javaExecutable();
        String command = "quoted 'value with spaces' | next > result.txt";

        assertThat(HostShell.bash(executable).launch(command)).containsExactly(executable.toString(), "-lc", command);
        assertThat(HostShell.powerShell(executable).launch(command))
                .containsExactly(
                        executable.toString(), "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", command);
    }

    private static Path javaExecutable() {
        String executable = System.getProperty("os.name", "")
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("win")
                ? "java.exe"
                : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable)
                .toAbsolutePath()
                .normalize();
    }
}
