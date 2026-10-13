package io.haifa.agent.execution.core.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.execution.api.ExecutionEnvironmentRef;
import io.haifa.agent.execution.api.ExecutionOutputObserver;
import io.haifa.agent.execution.api.ExecutionScratchSpaceSpec;
import io.haifa.agent.execution.api.SandboxProfileRef;
import io.haifa.agent.execution.api.ToolOutputPreviewPublisher;
import io.haifa.agent.tool.api.ToolDefinition;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The declared execution Tool timeout is a product definition; the SDK owns only the shared ceiling.
 * The Personal Assistant keeps its 30 second definition and a product aligned with a longer reference
 * tool timeout may declare up to {@link ExecutionToolConfiguration#MAXIMUM_DECLARED_TIMEOUT}.
 */
class ExecutionToolTimeoutBoundsTest {
    private static final ScriptRuntimeResolver RUNTIMES =
            new ScriptRuntimeResolver(ExecutionOperatingSystem.LINUX, List.of(ScriptRuntimeResolver.bash("/bin/bash")));

    @Test
    void keepsTheDefaultProductDefinitionAtThirtySeconds() {
        var configuration = configuration(Duration.ofSeconds(15), Duration.ofSeconds(30));
        ToolDefinition definition =
                ExecutionToolDefinitionFactory.create("profile@1", "configuration", true, false, Set.of("bash"));

        assertThat(ExecutionToolDefinitionFactory.DEFAULT_MAXIMUM_TIMEOUT).isEqualTo(Duration.ofSeconds(30));
        assertThat(ExecutionToolConfiguration.MAXIMUM_DECLARED_TIMEOUT).isEqualTo(Duration.ofMinutes(10));
        assertThat(configuration.maximumTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(definition.timeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void acceptsAProductDeclarationUpToTheSharedCeiling() {
        var configuration = configuration(Duration.ofSeconds(120), Duration.ofMinutes(10));
        ToolDefinition definition = ExecutionToolDefinitionFactory.create(
                "profile@1",
                configuration.identityDigest(),
                ExecutionScratchSpaceSpec.none().canonicalDigest(),
                Duration.ofMinutes(10),
                true,
                false,
                Set.of("bash"));

        assertThat(configuration.maximumTimeout()).isEqualTo(Duration.ofMinutes(10));
        assertThat(definition.timeout()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void rejectsADeclarationAboveTheSharedCeiling() {
        assertThatThrownBy(() -> configuration(
                        Duration.ofSeconds(120), Duration.ofMinutes(10).plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("execution tool timeout must be within 10 minutes");
    }

    @Test
    void rejectsADeclarationThatLeavesNoRoomForTheSchemaMinimum() {
        assertThatThrownBy(() -> ExecutionToolDefinitionFactory.create(
                        "profile@1",
                        "configuration",
                        "0".repeat(64),
                        Duration.ofMillis(999),
                        true,
                        false,
                        Set.of("bash")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("maximumTimeout is out of range");
    }

    private static ExecutionToolConfiguration configuration(Duration defaultTimeout, Duration maximumTimeout) {
        return new ExecutionToolConfiguration(
                new ExecutionEnvironmentRef(List.of("timeout-bounds-test")),
                new SandboxProfileRef("host-guarded", "1"),
                defaultTimeout,
                maximumTimeout,
                65_536,
                1_000,
                Optional.empty(),
                false,
                RUNTIMES,
                ExecutionOutputObserver.noop(),
                java.util.function.UnaryOperator.identity(),
                ExecutionScratchSpaceSpec.none(),
                ToolOutputPreviewPublisher.noop());
    }
}
