package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliVerificationProfileDiscoveryTest {
    @TempDir
    Path root;

    @Test
    void discoversBoundedBuildCandidatesWithoutReadingAHiddenTestOrTaskSpecificIdentifier() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Files.writeString(root.resolve("mvnw.cmd"), "wrapper");
        Files.writeString(root.resolve("pyproject.toml"), "[tool.pytest.ini_options]");
        Files.writeString(root.resolve("package-lock.json"), "{}");
        Files.createDirectories(root.resolve("src/test"));

        var discovery = CliVerificationProfileDiscovery.discoverWithSignals(root, "Windows 11");
        var profile = discovery.profile();

        assertThat(profile.candidates())
                .extracting(candidate -> candidate.command())
                .containsExactly(".\\mvnw.cmd test", "python -m pytest");
        assertThat(discovery.projectSignals())
                .containsExactly("mvnw.cmd", "package-lock.json", "pom.xml", "pyproject.toml", "src/test");
        assertThat(discovery.diagnostics()).isEmpty();
        assertThat(profile.instructionText())
                .contains("pom.xml", "pyproject.toml")
                .doesNotContain("Task ID", "hidden");
    }

    @Test
    void ignoresWrongTypeAndSymbolicLinkSignalsWithoutFollowingThem() throws Exception {
        Files.createDirectory(root.resolve("pom.xml"));
        Path target = Files.writeString(root.resolve("outside-pyproject.toml"), "[tool.pytest.ini_options]");
        createSymbolicLinkOrSkip(root.resolve("pyproject.toml"), target);

        var discovery = CliVerificationProfileDiscovery.discoverWithSignals(root, "Linux");

        assertThat(discovery.projectSignals()).doesNotContain("pom.xml", "pyproject.toml");
        assertThat(discovery.profile().candidates()).isEmpty();
        assertThat(discovery.diagnostics()).contains("pom.xml:INVALID", "pyproject.toml:INVALID");
    }

    private static void createSymbolicLinkOrSkip(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException exception) {
            org.junit.jupiter.api.Assumptions.abort("symbolic links are unavailable: " + exception.getMessage());
        }
    }
}
