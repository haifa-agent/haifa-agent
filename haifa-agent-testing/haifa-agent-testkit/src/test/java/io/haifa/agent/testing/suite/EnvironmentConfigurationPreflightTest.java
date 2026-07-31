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

    @Test
    void acceptsCurrentMultiModelConfigurationIncludingTemplates() throws Exception {
        Path environment = temporaryDirectory.resolve("environments/terminal/coding-agent.yaml.template");
        Files.createDirectories(environment.getParent());
        Files.writeString(
                environment,
                """
                models:
                  default: deepseek-chat
                  providers:
                    - id: deepseek
                      endpoint: https://api.deepseek.com
                      credentialRef: env://DEEPSEEK_API_KEY
                      models:
                        - id: deepseek-chat
                          providerModelId: deepseek-chat
                """);

        assertDoesNotThrow(() -> new EnvironmentConfigurationPreflight().validate(temporaryDirectory));
    }

    @Test
    void rejectsLegacySingleModelConfiguration() throws Exception {
        Path environment = temporaryDirectory.resolve("environments/cli/legacy.yaml");
        Files.createDirectories(environment.getParent());
        Files.writeString(
                environment,
                """
                model:
                  providerId: deepseek
                  modelId: deepseek-chat
                """);

        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> new EnvironmentConfigurationPreflight()
                        .validate(temporaryDirectory));

        assertTrue(exception.getMessage().contains("models.providers and models.default"));
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
