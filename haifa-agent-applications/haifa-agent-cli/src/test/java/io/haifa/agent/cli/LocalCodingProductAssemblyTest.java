package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.application.coding.terminal.jline.JLineTerminalLifecycle;
import io.haifa.agent.application.coding.terminal.session.LocalCodingSessionClient;
import io.haifa.agent.application.project.persistence.ProjectPersistenceConfiguration;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.core.model.continuation.AesGcmModelContinuationProtector;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.spec.SecretKeySpec;
import org.jline.terminal.Size;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalCodingProductAssemblyTest {
    @TempDir
    Path root;

    @Test
    void runnableTerminalEntryUsesTheProductionAssemblyWithAStubModelAndCleansUp() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("terminal-workspace"));
        CliConfiguration configuration = memoryConfiguration();
        AtomicInteger modelCalls = new AtomicInteger();
        var model = (io.haifa.agent.model.api.AgentChatModel) request -> {
            modelCalls.incrementAndGet();
            return response("terminal-answer");
        };
        AtomicReference<LocalCodingAgent> assembled = new AtomicReference<>();
        ByteArrayOutputStream terminalOutput = new ByteArrayOutputStream();
        var terminal = TerminalBuilder.builder()
                .system(false)
                .streams(
                        new ByteArrayInputStream(
                                "inspect the fixture\r/quit\r".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        terminalOutput)
                .type("xterm-256color")
                .size(new Size(120, 40))
                .build();
        var runner = new LocalCodingTerminalRunner(
                (selectedWorkspace, selectedConfiguration, output, traceObserver) -> {
                    LocalCodingAgent agent = LocalCodingAgent.create(
                            selectedWorkspace, selectedConfiguration, output, model, traceObserver);
                    assembled.set(agent);
                    return agent;
                },
                () -> JLineTerminalLifecycle.forTerminal(terminal));

        runner.run(workspace, configuration, new PrintStream(new ByteArrayOutputStream()), ignored -> {});
        awaitNoActiveRun(assembled.get());

        assertThat(modelCalls).hasValue(1);
        assertThat(terminalOutput.toString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("Haifa Coding Agent")
                .contains("inspect the fixture");
    }

    @Test
    void productionAssemblyRestartsSessionQueueCursorAndRejectsAnotherWorkspace() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("workspace-a"));
        Path otherWorkspace = Files.createDirectory(root.resolve("workspace-b"));
        Path database = root.resolve("coding-terminal.db");
        CliConfiguration configuration = sqliteConfiguration(database);
        CountDownLatch finishFirstRun = new CountDownLatch(1);
        AtomicInteger modelCalls = new AtomicInteger();
        var model = (io.haifa.agent.model.api.AgentChatModel) request -> {
            int call = modelCalls.incrementAndGet();
            if (call == 1) await(finishFirstRun);
            return response("answer-" + call);
        };
        io.haifa.agent.core.session.AgentSessionId sessionId;
        AgentRunId firstRunId;

        try (LocalCodingAgent first = agent(workspace, configuration, model)) {
            LocalCodingSessionClient client = client(first);
            var created = client.create(first.projectId(), "first task", "create-1");
            sessionId = created.summary().sessionId();
            firstRunId = created.activeRun().orElseThrow().runId();

            var page = awaitEvents(client, firstRunId);
            RunEventCursor acknowledged = page.items().getLast().cursor();
            client.acknowledgeCursor(sessionId, acknowledged);
            assertThat(client.open(sessionId).eventCursor()).contains(acknowledged);

            client.enqueueFollowUp(sessionId, firstRunId, "queued task", "follow-1");
            assertThat(client.restorableMessages(sessionId, 10))
                    .singleElement()
                    .satisfies(value -> assertThat(value.summary()).isEqualTo("queued task"));

            finishFirstRun.countDown();
            awaitTerminal(first, firstRunId);
        }

        AgentRunId dispatchedRunId;
        try (LocalCodingAgent second = agent(workspace, configuration, model)) {
            LocalCodingSessionClient client = client(second);
            var listed = client.list(second.projectId(), 10);
            assertThat(listed).extracting(value -> value.sessionId()).containsExactly(sessionId);
            var reopened = client.open(sessionId);
            dispatchedRunId = reopened.activeRun().orElseThrow().runId();
            assertThat(dispatchedRunId).isNotEqualTo(firstRunId);
            assertThat(reopened.eventCursor()).isEmpty();
            assertThat(client.restorableMessages(sessionId, 10)).isEmpty();

            awaitTerminal(second, dispatchedRunId);
            assertThat(client.reconcile(sessionId).activeRun()).isEmpty();
            assertThat(client.events(firstRunId, RunEventCursor.beforeFirst(firstRunId), 200)
                            .items())
                    .extracting(value -> value.eventId())
                    .doesNotHaveDuplicates();
        }

        try (LocalCodingAgent wrongWorkspace = agent(otherWorkspace, configuration, model)) {
            LocalCodingSessionClient client = client(wrongWorkspace);
            assertThat(client.list(wrongWorkspace.projectId(), 10)).isEmpty();
            assertThatThrownBy(() -> client.open(sessionId)).hasMessageContaining("Session is unavailable");
            assertThatThrownBy(() -> client.events(firstRunId, RunEventCursor.beforeFirst(firstRunId), 10))
                    .hasMessageContaining("Session is unavailable");
        }
        assertThat(modelCalls).hasValue(2);
    }

    private LocalCodingAgent agent(
            Path workspace, CliConfiguration configuration, io.haifa.agent.model.api.AgentChatModel model) {
        return LocalCodingAgent.create(
                workspace,
                configuration,
                new PrintStream(new ByteArrayOutputStream()),
                model,
                ignored -> {},
                protector());
    }

    private static LocalCodingSessionClient client(LocalCodingAgent agent) {
        return new LocalCodingSessionClient(
                agent.projectId(), agent.codingSessions(), agent.runtime(), agent.identifiers(), agent.time());
    }

    private static io.haifa.agent.runtime.api.RunEventPage awaitEvents(
            LocalCodingSessionClient client, AgentRunId runId) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(10);
        var page = client.events(runId, RunEventCursor.beforeFirst(runId), 200);
        while (page.items().isEmpty() && Instant.now().isBefore(deadline)) {
            Thread.sleep(20);
            page = client.events(runId, RunEventCursor.beforeFirst(runId), 200);
        }
        assertThat(page.items()).isNotEmpty();
        return page;
    }

    private static void awaitTerminal(LocalCodingAgent agent, AgentRunId runId) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(10);
        var snapshot = agent.runtime().find(runId).orElseThrow();
        while (!snapshot.status().isTerminal() && Instant.now().isBefore(deadline)) {
            Thread.sleep(20);
            snapshot = agent.runtime().find(runId).orElseThrow();
        }
        assertThat(snapshot.status()).isEqualTo(AgentRunStatus.COMPLETED);
    }

    private static void awaitNoActiveRun(LocalCodingAgent agent) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(10);
        LocalCodingSessionClient client = client(agent);
        while (Instant.now().isBefore(deadline)) {
            var sessions = client.list(agent.projectId(), 10);
            if (!sessions.isEmpty()
                    && client.open(sessions.getFirst().sessionId()).activeRun().isEmpty()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("terminal session did not reach an authoritative terminal state");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("model release timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("model wait interrupted", exception);
        }
    }

    private static AgentChatResponse response(String text) {
        return new AgentChatResponse(
                "stub-" + text,
                "stub-model",
                text,
                List.of(),
                ModelFinishReason.STOP,
                ModelUsage.unpriced(1, 1),
                "stub",
                Map.of());
    }

    private static CliConfiguration sqliteConfiguration(Path database) {
        return configuration(ProjectPersistenceConfiguration.sqlite(database, "env://HAIFA_TEST_CONTINUATION_KEY"));
    }

    private static CliConfiguration memoryConfiguration() {
        return configuration(ProjectPersistenceConfiguration.memory());
    }

    private static CliConfiguration configuration(ProjectPersistenceConfiguration persistence) {
        CliConfiguration defaults = CliConfiguration.defaults();
        CliConfiguration.Execution execution = defaults.execution();
        return new CliConfiguration(
                defaults.model(),
                defaults.enabledTools(),
                defaults.mcpServers(),
                defaults.web(),
                defaults.skills(),
                new CliConfiguration.Execution(
                        "host-guarded",
                        "allow",
                        execution.shell(),
                        execution.shellPath(),
                        execution.defaultTimeout(),
                        execution.maximumTimeout(),
                        execution.maxOutputBytes(),
                        execution.maxOutputLines(),
                        execution.maxProcesses(),
                        execution.inheritEnvironment(),
                        List.of()),
                ApprovalMode.AUTO,
                Duration.ofSeconds(10),
                defaults.maxIterations(),
                defaults.maxToolCalls(),
                persistence);
    }

    private static AesGcmModelContinuationProtector protector() {
        return new AesGcmModelContinuationProtector(
                new SecretKeySpec(new byte[32], "AES"), new java.security.SecureRandom());
    }
}
