package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.application.coding.terminal.session.LocalCodingSessionClient;
import io.haifa.agent.application.coding.terminal.tui4j.Tui4jTerminalIo;
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
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalCodingProductAssemblyTest {
    @TempDir
    Path root;

    @Test
    void nonInteractiveTerminalFailsBeforeTheProductRuntimeIsAssembled() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("non-interactive-workspace"));
        AtomicInteger assemblyCalls = new AtomicInteger();
        var runner = new LocalCodingTerminalRunner(
                (selectedWorkspace, selectedConfiguration, output, traceObserver) -> {
                    assemblyCalls.incrementAndGet();
                    throw new AssertionError("product assembly must not run");
                },
                () -> new Tui4jTerminalIo(Optional.empty(), Optional.empty(), List.of("TERM=dumb"), false, false));

        assertThatThrownBy(() -> runner.run(
                        workspace, memoryConfiguration(), new PrintStream(new ByteArrayOutputStream()), ignored -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("TUI_UNAVAILABLE");
        assertThat(assemblyCalls).hasValue(0);
    }

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
        var terminalInput = new ByteArrayInputStream(
                "inspect the fixture\r/quit\r".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var runner = new LocalCodingTerminalRunner(
                (selectedWorkspace, selectedConfiguration, output, traceObserver) -> {
                    LocalCodingAgent agent = LocalCodingAgent.create(
                            selectedWorkspace, selectedConfiguration, output, model, traceObserver);
                    assembled.set(agent);
                    return agent;
                },
                () -> Tui4jTerminalIo.streams(terminalInput, terminalOutput, List.of("TERM=xterm-256color")));

        runner.run(workspace, configuration, new PrintStream(new ByteArrayOutputStream()), ignored -> {});
        awaitNoActiveRun(assembled.get());

        assertThat(modelCalls).hasValue(1);
        assertThat(terminalOutput.toString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("Haifa Coding Agent");
    }

    @Test
    @Disabled("tui4j custom streams report 1x1 and truncate multi-frame output; use the ConPTY Gate B flow")
    void phaseThreeCommandsRunThroughTheProductionTerminalAssembly() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("phase-three-workspace"));
        Files.createDirectory(workspace.resolve("exports"));
        Files.writeString(
                workspace.resolve("AGENTS.md"),
                "# Synthetic terminal fixture\n\nUse only deterministic local evidence.\n");
        CliConfiguration configuration = memoryConfiguration();
        AtomicInteger modelCalls = new AtomicInteger();
        var model = (io.haifa.agent.model.api.AgentChatModel) request -> {
            modelCalls.incrementAndGet();
            return response("phase-three-answer");
        };
        AtomicReference<LocalCodingAgent> assembled = new AtomicReference<>();
        AtomicReference<Throwable> runnerFailure = new AtomicReference<>();
        ByteArrayOutputStream terminalOutput = new ByteArrayOutputStream();
        ByteArrayOutputStream applicationOutput = new ByteArrayOutputStream();
        PipedInputStream input = new PipedInputStream();
        PipedOutputStream writer = new PipedOutputStream(input);
        var runner = new LocalCodingTerminalRunner(
                (selectedWorkspace, selectedConfiguration, output, traceObserver) -> {
                    LocalCodingAgent agent = LocalCodingAgent.create(
                            selectedWorkspace, selectedConfiguration, output, model, traceObserver);
                    assembled.set(agent);
                    return agent;
                },
                () -> Tui4jTerminalIo.streams(input, terminalOutput, List.of("TERM=xterm-256color")));
        Thread runnerThread = Thread.ofPlatform()
                .name("phase-three-terminal-smoke")
                .start(() -> {
                    try {
                        runner.run(workspace, configuration, new PrintStream(applicationOutput), ignored -> {});
                    } catch (Throwable throwable) {
                        runnerFailure.set(throwable);
                    }
                });

        try {
            awaitTerminalText(terminalOutput, "Haifa Coding Agent");
            typeLine(writer, "inspect the phase three fixture");
            awaitAgent(assembled);
            awaitNoActiveRun(assembled.get());
            LocalCodingSessionClient authoritativeClient = client(assembled.get());
            var firstSession = authoritativeClient
                    .list(assembled.get().projectId(), 10)
                    .getFirst()
                    .sessionId();

            typeLine(writer, "/rename phase-three-smoke");
            awaitCondition(
                    () -> authoritativeClient
                            .open(firstSession)
                            .summary()
                            .displayName()
                            .equals("phase-three-smoke"),
                    "renamed session");

            int beforeCompaction = terminalOutput.size();
            typeLine(writer, "/compact");
            awaitTerminalActivity(terminalOutput, beforeCompaction);

            List<String> resourcesBeforeReload = assembled.get().loadedResources();
            Files.writeString(
                    workspace.resolve("AGENTS.md"),
                    "# Synthetic terminal fixture\n\nReloaded instructions apply only to future Runs.\n");
            typeLine(writer, "/reload");
            awaitCondition(
                    () -> !assembled.get().loadedResources().equals(resourcesBeforeReload), "project resource reload");

            typeLine(writer, "/export exports/session.jsonl");
            awaitFile(workspace.resolve("exports/session.jsonl"));

            typeLine(writer, "/delete");
            awaitTerminalText(terminalOutput, "Delete current session?");
            chooseFirst(writer);
            awaitCondition(
                    () -> authoritativeClient
                            .list(assembled.get().projectId(), 10)
                            .isEmpty(),
                    "deleted session");

            typeLine(writer, "create a second session");
            awaitModelCalls(modelCalls, 2);
            awaitNoActiveRun(assembled.get());
            var secondSession = authoritativeClient
                    .list(assembled.get().projectId(), 10)
                    .getFirst()
                    .sessionId();

            typeLine(writer, "/archive");
            awaitTerminalText(terminalOutput, "Archive current session?");
            chooseFirst(writer);
            awaitCondition(
                    () -> authoritativeClient.open(secondSession).summary().status()
                            == io.haifa.agent.core.session.AgentSessionStatus.ARCHIVED,
                    "archived session");

            typeLine(writer, "/quit");
            runnerThread.join(10_000);
        } finally {
            if (runnerThread.isAlive()) {
                typeLine(writer, "/quit");
                runnerThread.join(2_000);
            }
            writer.close();
            recordTerminalArtifacts(terminalOutput, applicationOutput);
        }

        assertThat(runnerThread.isAlive()).isFalse();
        assertThat(runnerFailure.get()).isNull();
        assertThat(modelCalls).hasValue(2);
        assertThat(Files.readString(workspace.resolve("exports/session.jsonl")))
                .contains("\"schemaVersion\":\"haifa.coding-session-export/1\"")
                .contains("phase-three-answer")
                .containsOnlyOnce("\"preview\":\"inspect the phase three fixture\"");
        assertThat(terminalOutput.toString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("\033[?1049h")
                .contains("\033[?1049l")
                .doesNotContain("COMMAND_UNKNOWN");
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

    private static void awaitAgent(AtomicReference<LocalCodingAgent> assembled) throws InterruptedException {
        awaitCondition(() -> assembled.get() != null, "production agent assembly");
    }

    private static void awaitTerminalText(ByteArrayOutputStream output, String expected) throws InterruptedException {
        awaitCondition(
                () -> output.toString(java.nio.charset.StandardCharsets.UTF_8).contains(expected),
                "terminal text: " + expected);
    }

    private static void awaitFile(Path file) throws InterruptedException {
        awaitCondition(() -> Files.isRegularFile(file), "terminal export");
    }

    private static void awaitTerminalActivity(ByteArrayOutputStream output, int startingSize)
            throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(10);
        int previousSize = output.size();
        Instant lastChange = Instant.now();
        while (Instant.now().isBefore(deadline)) {
            int currentSize = output.size();
            if (currentSize != previousSize) {
                previousSize = currentSize;
                lastChange = Instant.now();
            }
            if (currentSize > startingSize && Instant.now().isAfter(lastChange.plusMillis(250))) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("timed out waiting for terminal output activity");
    }

    private static void awaitModelCalls(AtomicInteger calls, int expected) throws InterruptedException {
        awaitCondition(() -> calls.get() >= expected, "model call " + expected);
    }

    private static void awaitCondition(java.util.function.BooleanSupplier condition, String description)
            throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(10);
        while (!condition.getAsBoolean() && Instant.now().isBefore(deadline)) {
            Thread.sleep(20);
        }
        if (!condition.getAsBoolean()) {
            throw new AssertionError("timed out waiting for " + description);
        }
    }

    private static void typeLine(PipedOutputStream writer, String value) throws IOException, InterruptedException {
        for (byte character : (value + "\r").getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            writer.write(character);
            writer.flush();
            Thread.sleep(2);
        }
    }

    private static void chooseFirst(PipedOutputStream writer) throws IOException {
        writer.write("\033[A\r".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        writer.flush();
    }

    private static void recordTerminalArtifacts(
            ByteArrayOutputStream terminalOutput, ByteArrayOutputStream applicationOutput) throws IOException {
        String configured = System.getProperty("haifa.terminal.recording.dir");
        if (configured == null || configured.isBlank()) {
            return;
        }
        Path directory = Path.of(configured).toAbsolutePath().normalize();
        Files.createDirectories(directory);
        Files.write(directory.resolve("product-flow.ansi"), terminalOutput.toByteArray());
        Files.write(directory.resolve("application.log"), applicationOutput.toByteArray());
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
