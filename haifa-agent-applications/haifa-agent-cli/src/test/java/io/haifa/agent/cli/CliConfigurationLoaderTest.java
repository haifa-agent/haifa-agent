package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.model.api.ModelCapability;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CliConfigurationLoaderTest {
    @Test
    void freezesDeepSeekThinkingForCliRuns() {
        var snapshot = LocalCodingAgent.modelSnapshot(CliConfiguration.defaults());

        assertThat(snapshot.capabilities()).contains(ModelCapability.REASONING);
        assertThat(snapshot.providerOptions())
                .containsEntry("thinking", "enabled")
                .containsEntry("reasoning_effort", "high");
        assertThat(snapshot.invocationOptions())
                .containsEntry("thinking", "enabled")
                .containsEntry("reasoning_effort", "high")
                .containsEntry("requires_reasoning_continuation", true);
    }

    @Test
    void loadsExplicitYamlConfiguration() throws Exception {
        Path configuration = Files.createTempFile("haifa-cli", ".yaml");
        Files.writeString(
                configuration,
                """
                model:
                  providerId: local
                  modelId: test-model
                  endpoint: http://localhost:8080
                  credentialRef: env://TEST_KEY
                tools:
                  enabled: [file.read, file.write]
                approval:
                  mode: deny
                runtime:
                  maxIterations: 3
                  maxToolCalls: 4
                  maxWallTimeMillis: 120000
                """);

        CliConfiguration result = new CliConfigurationLoader()
                .load(
                        CliArguments.parse(new String[] {"-m", "test", "--config", configuration.toString()}),
                        Path.of("."));

        assertThat(result.model().modelId()).isEqualTo("test-model");
        assertThat(result.enabledTools()).containsExactlyInAnyOrder("file.read", "file.write");
        assertThat(result.approval()).isEqualTo(ApprovalMode.DENY);
        assertThat(result.timeout()).isEqualTo(java.time.Duration.ofMillis(120000));
    }
}
