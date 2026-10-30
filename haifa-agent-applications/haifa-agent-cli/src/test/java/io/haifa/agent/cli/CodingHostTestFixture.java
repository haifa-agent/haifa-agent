package io.haifa.agent.cli;

import io.haifa.agent.application.project.persistence.ProjectPersistenceConfiguration;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.runtime.core.model.continuation.AesGcmModelContinuationProtector;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.crypto.spec.SecretKeySpec;

/** Shared CLI-only test helpers for the real Coding product/SQLite assembly and its Host tests. */
final class CodingHostTestFixture {
    private CodingHostTestFixture() {}

    static CliConfiguration sqliteConfiguration(Path database) {
        CliConfiguration defaults = CliConfiguration.defaults();
        CliConfiguration.Execution execution = defaults.execution();
        return CliConfigurationBuilder.from(defaults)
                .execution(new CliConfiguration.Execution(
                        "host-guarded",
                        execution.shell(),
                        execution.shellPath(),
                        execution.maximumTimeout(),
                        execution.maxOutputBytes(),
                        execution.maxOutputLines(),
                        execution.maxProcesses(),
                        execution.inheritEnvironment()))
                .approval(ApprovalMode.AUTO)
                .timeout(Duration.ofSeconds(10))
                .persistence(ProjectPersistenceConfiguration.sqlite(database, "env://HAIFA_TEST_CONTINUATION_KEY"))
                .build();
    }

    static AesGcmModelContinuationProtector protector() {
        return new AesGcmModelContinuationProtector(
                new SecretKeySpec(new byte[32], "AES"), new java.security.SecureRandom());
    }

    static AgentChatResponse response(String text) {
        return new AgentChatResponse(
                "fixture-" + text,
                "stub-model",
                text,
                List.of(),
                ModelFinishReason.STOP,
                ModelUsage.unpriced(1, 1),
                "stub",
                Map.of());
    }

    static AgentChatResponse toolResponse(String id, String name, Map<String, Object> arguments) {
        return new AgentChatResponse(
                id,
                "stub-model",
                "",
                List.of(new ModelToolCall(new ProviderToolCallCorrelationId(id + "-call"), name, arguments)),
                ModelFinishReason.TOOL_CALLS,
                ModelUsage.unpriced(1, 1),
                "stub",
                Map.of());
    }

    static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("model release timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("model wait interrupted", exception);
        }
    }
}
