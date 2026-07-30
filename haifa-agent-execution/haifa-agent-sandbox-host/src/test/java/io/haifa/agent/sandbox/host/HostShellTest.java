package io.haifa.agent.sandbox.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class HostShellTest {
    @Test
    void compilesTrustedBashAndPowerShellLaunchArgumentsWithoutExposingCommandText() {
        Path executable = javaExecutable();
        String command = "quoted 'value with spaces' | next > result.txt";

        assertThat(HostShell.bash(executable).launch(command)).containsExactly(executable.toString(), "-lc", command);
        var powerShellLaunch = HostShell.powerShell(executable).launch(command);
        assertThat(powerShellLaunch)
                .hasSize(6)
                .startsWith(executable.toString(), "-NoLogo", "-NoProfile", "-NonInteractive", "-Command")
                .noneMatch(value -> value.contains(command));

        String wrapper = powerShellLaunch.get(5);
        assertThat(wrapper)
                .contains(
                        "[Console]::InputEncoding = $__haifaUtf8",
                        "[Console]::OutputEncoding = $__haifaUtf8",
                        "$OutputEncoding = $__haifaUtf8",
                        Base64.getEncoder().encodeToString(command.getBytes(StandardCharsets.UTF_8)))
                .doesNotContain(command);
    }

    @Test
    void windowsPowerShellLaunchProducesStrictUtf8ForOutputAndSyntaxErrors() throws Exception {
        assumeTrue(isWindows());
        HostShell shell = HostShell.auto();

        Process success = new ProcessBuilder(shell.launch("Write-Output '中文输出'")).start();
        byte[] successOutput = success.getInputStream().readAllBytes();
        byte[] successError = success.getErrorStream().readAllBytes();
        assertThat(success.waitFor(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS))
                .isTrue();
        assertThat(success.exitValue()).isZero();
        assertThat(decodeStrictUtf8(successOutput)).contains("中文输出");
        assertThat(decodeStrictUtf8(successError)).doesNotContain("\uFFFD");

        Process syntaxError = new ProcessBuilder(shell.launch("Write-Output \"未结束")).start();
        byte[] errorOutput = syntaxError.getInputStream().readAllBytes();
        byte[] errorText = syntaxError.getErrorStream().readAllBytes();
        assertThat(syntaxError.waitFor(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS))
                .isTrue();
        assertThat(syntaxError.exitValue()).isNotZero();
        assertThat(decodeStrictUtf8(errorOutput)).isEmpty();
        assertThat(decodeStrictUtf8(errorText)).isNotBlank();
    }

    private static String decodeStrictUtf8(byte[] bytes) throws java.nio.charset.CharacterCodingException {
        return StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win");
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
