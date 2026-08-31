package io.haifa.agent.personalassistant.server.development;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersonalAssistantRealEnvironmentMainTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void findsRepositoryFromNestedModuleDirectory() throws Exception {
        Files.writeString(temporaryDirectory.resolve("pom.xml"), "<project/>");
        Files.createDirectories(temporaryDirectory.resolve("scripts"));
        Files.writeString(temporaryDirectory.resolve("scripts/real_environment.py"), "# launcher");
        Path module = Files.createDirectories(temporaryDirectory.resolve("module/src/test"));

        assertThat(PersonalAssistantRealEnvironmentMain.findRepository(module))
                .isEqualTo(temporaryDirectory.toAbsolutePath().normalize());
    }

    @Test
    void refusesDirectoryWithoutTheCanonicalPythonImplementation() {
        assertThatThrownBy(() -> PersonalAssistantRealEnvironmentMain.findRepository(temporaryDirectory))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scripts/real_environment.py");
    }

    @Test
    void delegatesToClasspathModeAndPreservesUserArguments() {
        assertThat(PersonalAssistantRealEnvironmentMain.command(
                        temporaryDirectory, "python", new String[] {"--default-model-id", "antigravity-gemini"}))
                .containsExactly(
                        "python",
                        temporaryDirectory
                                .resolve("scripts/real_environment.py")
                                .toString(),
                        "--default-model-id",
                        "antigravity-gemini",
                        "--backend-launch-mode",
                        "classpath");
    }

    @Test
    void makesRelativeIdeClasspathEntriesIndependentOfBackendWorkingDirectory() {
        String raw = String.join(File.pathSeparator, "target/test-classes", "target/classes");

        assertThat(PersonalAssistantRealEnvironmentMain.absoluteClasspath(raw, temporaryDirectory))
                .isEqualTo(String.join(
                        File.pathSeparator,
                        temporaryDirectory
                                .resolve("target/test-classes")
                                .toAbsolutePath()
                                .normalize()
                                .toString(),
                        temporaryDirectory
                                .resolve("target/classes")
                                .toAbsolutePath()
                                .normalize()
                                .toString()));
    }
}
