package io.haifa.agent.testing.suite;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EnvironmentConfigurationPreflightTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsMaximumProtectedPayloadLimit() throws Exception {
        writeEnvironment(1_048_576);

        assertDoesNotThrow(() -> new EnvironmentConfigurationPreflight().validate(temporaryDirectory));
    }

    @Test
    void rejectsEnvironmentThatProductWouldRejectAtStartup() throws Exception {
        Path environment = writeEnvironment(4_194_304);

        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> new EnvironmentConfigurationPreflight()
                        .validate(temporaryDirectory));

        assertTrue(exception.getMessage().contains("environments/cli/interaction-live.yaml"));
    }

    private Path writeEnvironment(int maximumPayloadBytes) throws Exception {
        Path environment = temporaryDirectory.resolve("environments/cli/interaction-live.yaml");
        Files.createDirectories(environment.getParent());
        Files.writeString(
                environment,
                """
                persistence:
                  maximumPayloadBytes: %d
                """
                        .formatted(maximumPayloadBytes));
        return environment;
    }
}
