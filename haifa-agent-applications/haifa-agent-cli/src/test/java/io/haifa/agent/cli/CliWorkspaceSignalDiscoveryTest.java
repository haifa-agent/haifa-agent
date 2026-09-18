package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliWorkspaceSignalDiscoveryTest {
    @TempDir
    Path root;

    @Test
    void discoversBoundedProjectSignalsWithoutReadingHiddenOrTaskSpecificFiles() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Files.writeString(root.resolve("mvnw.cmd"), "wrapper");
        Files.writeString(root.resolve("pyproject.toml"), "[tool.pytest.ini_options]");
        Files.writeString(root.resolve("package-lock.json"), "{}");
        Files.createDirectories(root.resolve("src/test"));

        var discovery = CliWorkspaceSignalDiscovery.discoverWithSignals(root, "Windows 11");

        assertThat(discovery.projectSignals())
                .containsExactly("mvnw.cmd", "package-lock.json", "pom.xml", "pyproject.toml", "src/test");
        assertThat(discovery.diagnostics()).isEmpty();
    }

    @Test
    void ignoresWrongTypeAndSymbolicLinkSignalsWithoutFollowingThem() throws Exception {
        Files.createDirectory(root.resolve("pom.xml"));
        Path target = Files.writeString(root.resolve("outside-pyproject.toml"), "[tool.pytest.ini_options]");
        createSymbolicLinkOrSkip(root.resolve("pyproject.toml"), target);

        var discovery = CliWorkspaceSignalDiscovery.discoverWithSignals(root, "Linux");

        assertThat(discovery.projectSignals()).doesNotContain("pom.xml", "pyproject.toml");
        assertThat(discovery.diagnostics()).contains("pom.xml:INVALID", "pyproject.toml:INVALID");
    }

    @Test
    void reportsTheExistingPlatformVerificationEntriesAsSignalsForEveryOperatingSystem() throws Exception {
        Files.writeString(root.resolve("verify.ps1"), "exit 0");
        Files.writeString(root.resolve("verify.sh"), "exit 0");

        var windows = CliWorkspaceSignalDiscovery.discoverWithSignals(root, "Windows 11");
        var linux = CliWorkspaceSignalDiscovery.discoverWithSignals(root, "Linux");

        assertThat(windows.projectSignals()).containsExactly("verify.ps1", "verify.sh");
        assertThat(linux.projectSignals()).containsExactly("verify.ps1", "verify.sh");
    }

    private static void createSymbolicLinkOrSkip(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException exception) {
            org.junit.jupiter.api.Assumptions.abort("symbolic links are unavailable: " + exception.getMessage());
        }
    }
}
