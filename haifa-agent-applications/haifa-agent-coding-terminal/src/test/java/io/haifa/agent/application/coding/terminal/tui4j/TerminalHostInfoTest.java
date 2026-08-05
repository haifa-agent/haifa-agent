package io.haifa.agent.application.coding.terminal.tui4j;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TerminalHostInfoTest {
    @Test
    void collectsAllowlistedMacOsAndTerminalVersionFacts() {
        TerminalHostInfo host = TerminalHostInfo.detect(
                Map.of(
                        "os.name", "Mac OS X",
                        "os.version", "15.6.1",
                        "os.arch", "aarch64",
                        "java.version", "21.0.3"),
                List.of("TERM=xterm-256color", "TERM_PROGRAM=Apple_Terminal", "TERM_PROGRAM_VERSION=2.15"));

        assertThat(host.operatingSystem()).isEqualTo(TerminalHostInfo.OperatingSystem.MACOS);
        assertThat(host.osName()).isEqualTo("Mac OS X");
        assertThat(host.osVersion()).isEqualTo("15.6.1");
        assertThat(host.architecture()).isEqualTo("aarch64");
        assertThat(host.javaVersion()).isEqualTo("21.0.3");
        assertThat(host.terminalProgram()).contains("Apple_Terminal");
        assertThat(host.terminalProgramVersion()).contains("2.15");
    }

    @Test
    void recognizesDarwinWindowsLinuxAndUnknownHostsWithoutCollectingArbitraryEnvironment() {
        assertThat(host("Darwin").operatingSystem()).isEqualTo(TerminalHostInfo.OperatingSystem.MACOS);
        assertThat(host("Windows 11").operatingSystem()).isEqualTo(TerminalHostInfo.OperatingSystem.WINDOWS);
        assertThat(host("Linux").operatingSystem()).isEqualTo(TerminalHostInfo.OperatingSystem.LINUX);
        assertThat(host("Plan 9").operatingSystem()).isEqualTo(TerminalHostInfo.OperatingSystem.OTHER);
        assertThat(host("Linux").terminalProgram()).isEmpty();
    }

    private static TerminalHostInfo host(String osName) {
        return TerminalHostInfo.detect(
                Map.of(
                        "os.name", osName,
                        "os.version", "1",
                        "os.arch", "test-arch",
                        "java.version", "21"),
                List.of("SECRET=not-collected"));
    }
}
