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

        var profile = CliVerificationProfileDiscovery.discover(root, "Windows 11");

        assertThat(profile.candidates())
                .extracting(candidate -> candidate.command())
                .containsExactly(".\\mvnw.cmd test", "python -m pytest");
        assertThat(profile.instructionText())
                .contains("pom.xml", "pyproject.toml")
                .doesNotContain("Task ID", "hidden");
    }
}
