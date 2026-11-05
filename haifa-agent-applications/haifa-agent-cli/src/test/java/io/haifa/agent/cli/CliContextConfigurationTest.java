package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliContextConfigurationTest {
    @TempDir
    Path root;

    @Test
    void defaultsStayLegacyAndExplicitReadersSurviveRollback() throws Exception {
        var defaults = load("{}");
        assertThat(defaults.context()).isEqualTo(CliConfiguration.Context.defaults());
        assertThat(defaults.context().runtimeOptions()).isEmpty();
        var enabled = load("context:\n  policy: unified-context-v1\n  targetTokens: 4096\n  historyRetrieval: true\n");
        assertThat(enabled.context().runtimeOptions())
                .containsEntry("haifa.runtime.context_budget_policy", "unified-context-v1")
                .containsEntry("haifa.runtime.working_set_target_tokens", 4096);
        var rollback = load("context:\n  policy: legacy\n  historyRetrieval: true\n");
        assertThat(rollback.context().runtimeOptions()).isEmpty();
        assertThat(rollback.context().historyRetrieval()).isTrue();
    }

    @Test
    void rejectsUnsupportedOrUnsafeContextConfigurationBeforeAssembly() {
        var invalid = java.util.Map.of(
                "policy: unknown", "context.policy",
                "policy: unified-context-v1", "context.historyRetrieval",
                "targetTokens: 127", "context.targetTokens",
                "targetTokens: 65537", "context.targetTokens",
                "historyRetrieval: yesplease", "must be boolean",
                "automaticMaintenance: true", "not supported",
                "typo: true", "unsupported context");
        for (var entry : invalid.entrySet()) {
            assertThatThrownBy(() -> load("context:\n  " + entry.getKey() + "\n"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(entry.getValue());
        }
    }

    private CliConfiguration load(String yaml) throws Exception {
        Path file = root.resolve("config.yaml");
        Files.writeString(file, yaml);
        return new CliConfigurationLoader()
                .load(
                        CliArguments.parse(new String[] {"--config", file.toString()}),
                        root,
                        Optional.empty(),
                        Path.of("absent.yaml"));
    }
}
