package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelUsage;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalCodingAgentTest {
    @TempDir
    Path workspace;

    @Test
    void denyRemovesExecutionBeforeToolCatalogDisclosure() {
        CliConfiguration defaults = CliConfiguration.defaults();
        var denied = new CliConfiguration(
                defaults.model(),
                defaults.enabledTools(),
                defaults.mcpServers(),
                defaults.execution(),
                ApprovalMode.DENY,
                defaults.timeout(),
                defaults.maxIterations(),
                defaults.maxToolCalls());

        assertThat(LocalCodingAgent.effectiveBuiltInTools(denied)).doesNotContain("execution.run");
        assertThat(LocalCodingAgent.effectiveBuiltInTools(defaults)).contains("execution.run");
    }

    @Test
    void stubModelRunsGeneralShellThroughTheCliAssembly() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String command =
                isWindows() ? "Set-Content -NoNewline -Path shell-e2e.txt -Value stub" : "printf stub > shell-e2e.txt";
        var model = (io.haifa.agent.model.api.AgentChatModel) request -> {
            if (calls.incrementAndGet() == 1) {
                assertThat(request.tools())
                        .extracting(io.haifa.agent.model.api.ModelToolSpecification::name)
                        .contains("execution_run");
                return new AgentChatResponse(
                        "cli-shell-1",
                        "stub-model",
                        "",
                        List.of(new ModelToolCall(
                                new ProviderToolCallCorrelationId("shell-call-1"),
                                "execution_run",
                                Map.of(
                                        "command",
                                        command,
                                        "workdir",
                                        ".",
                                        "timeoutMillis",
                                        5000,
                                        "description",
                                        "Write a test file"))),
                        ModelFinishReason.TOOL_CALLS,
                        ModelUsage.unpriced(10, 3),
                        "stub",
                        Map.of());
            }
            assertThat(request.messages())
                    .anyMatch(message -> message.role() == ModelMessageRole.TOOL
                            && message.providerCorrelationId()
                                    .orElseThrow()
                                    .value()
                                    .equals("shell-call-1")
                            && message.toolResultData().get("status").equals("SUCCEEDED")
                            && message.toolResultData().containsKey("fileChangeSetId"));
            return new AgentChatResponse(
                    "cli-shell-2",
                    "stub-model",
                    "shell complete",
                    List.of(),
                    ModelFinishReason.STOP,
                    ModelUsage.unpriced(15, 4),
                    "stub",
                    Map.of());
        };
        CliConfiguration defaults = CliConfiguration.defaults();
        var automatic = new CliConfiguration(
                defaults.model(),
                defaults.enabledTools(),
                defaults.mcpServers(),
                defaults.execution(),
                ApprovalMode.AUTO,
                Duration.ofSeconds(15),
                defaults.maxIterations(),
                defaults.maxToolCalls());
        var renderedOutput = new ByteArrayOutputStream();

        try (var agent = LocalCodingAgent.create(
                workspace, automatic, new PrintStream(renderedOutput, true, StandardCharsets.UTF_8), model)) {
            var accepted = agent.start("Write the representative file with the general shell tool.");
            Instant deadline = Instant.now().plusSeconds(10);
            var snapshot = agent.runtime().find(accepted.runId()).orElseThrow();
            while (!snapshot.status().isTerminal() && Instant.now().isBefore(deadline)) {
                Thread.sleep(25);
                snapshot = agent.runtime().find(accepted.runId()).orElseThrow();
            }

            assertThat(snapshot.status()).isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(snapshot.output()).contains("shell complete");
        }
        assertThat(Files.readString(workspace.resolve("shell-e2e.txt"))).isEqualTo("stub");
        assertThat(calls).hasValue(2);
    }

    @Test
    void fileListAcceptsTheDisclosedDotWorkspaceRoot() throws Exception {
        Files.writeString(workspace.resolve("visible.txt"), "fixture", StandardCharsets.UTF_8);
        AtomicInteger calls = new AtomicInteger();
        var model = (io.haifa.agent.model.api.AgentChatModel) request -> {
            if (calls.incrementAndGet() == 1) {
                return new AgentChatResponse(
                        "cli-list-1",
                        "stub-model",
                        "",
                        List.of(new ModelToolCall(
                                new ProviderToolCallCorrelationId("list-call-1"), "file_list", Map.of("path", "."))),
                        ModelFinishReason.TOOL_CALLS,
                        ModelUsage.unpriced(10, 3),
                        "stub",
                        Map.of());
            }
            assertThat(request.messages())
                    .anyMatch(message -> message.role() == ModelMessageRole.TOOL
                            && message.providerCorrelationId()
                                    .orElseThrow()
                                    .value()
                                    .equals("list-call-1"));
            return new AgentChatResponse(
                    "cli-list-2",
                    "stub-model",
                    "listed root",
                    List.of(),
                    ModelFinishReason.STOP,
                    ModelUsage.unpriced(15, 4),
                    "stub",
                    Map.of());
        };

        try (var agent = LocalCodingAgent.create(
                workspace,
                CliConfiguration.defaults(),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                model)) {
            var accepted = agent.start("List the workspace root.");
            Instant deadline = Instant.now().plusSeconds(10);
            var snapshot = agent.runtime().find(accepted.runId()).orElseThrow();
            while (!snapshot.status().isTerminal() && Instant.now().isBefore(deadline)) {
                Thread.sleep(25);
                snapshot = agent.runtime().find(accepted.runId()).orElseThrow();
            }

            assertThat(snapshot.status()).isEqualTo(AgentRunStatus.COMPLETED);
        }
        assertThat(calls).hasValue(2);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win");
    }
}
