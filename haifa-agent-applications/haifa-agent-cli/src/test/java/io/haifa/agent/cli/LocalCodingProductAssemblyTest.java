package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.application.coding.terminal.tui4j.Tui4jTerminalIo;
import io.haifa.agent.application.project.persistence.ProjectPersistenceConfiguration;
import io.haifa.agent.application.project.product.coding.client.LocalCodingSessionClient;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPayloads;
import io.haifa.agent.runtime.core.model.continuation.AesGcmModelContinuationProtector;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("slow")
class LocalCodingProductAssemblyTest {
    @TempDir
    Path root;

    @Test
    void sessionConsentApprovesTheNextOrdinaryToolThroughTheRealRuntime() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("session-approval"));
        Path file = workspace.resolve("output.txt");
        var configuration = CliConfigurationBuilder.from(memoryConfiguration())
                .approval(ApprovalMode.ASK)
                .build();
        var calls = new AtomicInteger();
        io.haifa.agent.model.api.AgentChatModel model = request -> switch (calls.incrementAndGet()) {
            case 1 -> toolResponse("write-first", "file_write", Map.of("path", file.toString(), "content", "before\n"));
            case 2 ->
                toolResponse(
                        "patch-next",
                        "file_patch",
                        Map.of(
                                "patch",
                                "*** Begin Patch\n*** Update File: " + file
                                        + "\n@@\n-before\n+after\n*** End Patch\n"));
            default -> response("done");
        };
        try (var agent = agent(workspace, configuration, model)) {
            var client = client(agent);
            var session = client.create(agent.projectId(), "Update the file", "session-approval");
            var run = session.activeRun().orElseThrow().runId();
            awaitCondition(() -> client.pendingInteraction(run).isPresent(), "first approval");
            var first = client.pendingInteraction(run).orElseThrow();
            assertThat(client.prepareApproval(first)).hasValueSatisfying(prompt -> assertThat(prompt.allowForSession())
                    .isTrue());
            client.allowForSession(first, "allow-session");
            awaitCondition(
                    () -> client.pendingInteraction(run)
                            .filter(pending -> !pending.requestId().equals(first.requestId()))
                            .isPresent(),
                    "next approval");
            var next = client.pendingInteraction(run).orElseThrow();
            assertThat(client.prepareApproval(next)).isEmpty();
            awaitTerminal(agent, run);
            assertThat(Files.readString(file)).isEqualTo("after\n");
            assertThat(calls).hasValue(3);
        }
    }

    @Test
    void freezesSiliconFlowDialectAndNativeStreamingForProductAssembly() {
        URI endpoint = URI.create("https://api.siliconflow.cn/v1");
        var model = new CliConfiguration.Model(
                "siliconflow",
                "SiliconFlow",
                "deepseek-ai/DeepSeek-V4-Flash",
                endpoint,
                endpoint,
                "env://SILICONFLOW_API_KEY",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                io.haifa.agent.model.openai.OpenAiCompatibleDialects.SILICONFLOW,
                true,
                null,
                null,
                "siliconflow-deepseek-v4-flash",
                "SiliconFlow DeepSeek V4 Flash",
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                1_000_000,
                8_192);

        var snapshot = LocalCodingAgent.modelSnapshot(model);

        assertThat(snapshot.nativeStreaming()).isTrue();
        assertThat(snapshot.dialect()).isEqualTo(io.haifa.agent.model.openai.OpenAiCompatibleDialects.SILICONFLOW);
        assertThat(snapshot.providerOptions()).containsEntry("endpoint_host", "api.siliconflow.cn");
    }

    @Test
    void rendersExactWorkspaceAttachmentDetailsForApproval() {
        String prompt = LocalCodingAgent.workspaceAttachmentApprovalPrompt(Map.of(
                "path", "D:\\workspace\\haifa-agent-docs",
                "mode", "develop"));

        assertThat(prompt)
                .contains("Attach additional workspace directory")
                .contains("Path: D:\\workspace\\haifa-agent-docs")
                .contains("Mode: develop")
                .contains("persisted locally and remains revocable");
    }

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
                        workspace,
                        memoryConfiguration(),
                        io.haifa.agent.application.coding.terminal.application.CodingTerminalStartup.empty(),
                        new PrintStream(new ByteArrayOutputStream()),
                        ignored -> {}))
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
            int call = modelCalls.incrementAndGet();
            return call == 1 ? readWorkspace("terminal-read", workspace) : response("terminal-answer");
        };
        AtomicReference<LocalCodingAgent> assembled = new AtomicReference<>();
        AtomicReference<Throwable> runnerFailure = new AtomicReference<>();
        ByteArrayOutputStream terminalOutput = new ByteArrayOutputStream();
        PipedInputStream terminalInput = new PipedInputStream();
        PipedOutputStream writer = new PipedOutputStream(terminalInput);
        var runner = new LocalCodingTerminalRunner(
                (selectedWorkspace, selectedConfiguration, output, traceObserver) -> {
                    StandaloneCodingAgent standalone = StandaloneCodingAgents.open(
                            selectedWorkspace, selectedConfiguration, output, model, traceObserver);
                    LocalCodingAgent agent = standalone.localAgent();
                    assembled.set(agent);
                    return standalone;
                },
                () -> Tui4jTerminalIo.streams(terminalInput, terminalOutput, List.of("TERM=xterm-256color")));

        Thread runnerThread = Thread.ofPlatform().name("terminal-entry-smoke").start(() -> {
            try {
                runner.run(
                        workspace,
                        configuration,
                        io.haifa.agent.application.coding.terminal.application.CodingTerminalStartup.empty(),
                        new PrintStream(new ByteArrayOutputStream()),
                        ignored -> {});
            } catch (Throwable throwable) {
                runnerFailure.set(throwable);
            }
        });

        try {
            awaitTerminalText(terminalOutput, "Haifa Coding Agent");
            typeLine(writer, "inspect the fixture");
            awaitAgent(assembled);
            awaitNoActiveRun(assembled.get());
            typeLine(writer, "/quit");
            runnerThread.join(10_000);
        } finally {
            if (runnerThread.isAlive()) {
                typeLine(writer, "/quit");
                runnerThread.join(2_000);
            }
            writer.close();
        }

        assertThat(runnerThread.isAlive()).isFalse();
        assertThat(runnerFailure.get()).isNull();
        assertThat(modelCalls).hasValue(2);
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
                    StandaloneCodingAgent standalone = StandaloneCodingAgents.open(
                            selectedWorkspace, selectedConfiguration, output, model, traceObserver);
                    LocalCodingAgent agent = standalone.localAgent();
                    assembled.set(agent);
                    return standalone;
                },
                () -> Tui4jTerminalIo.streams(input, terminalOutput, List.of("TERM=xterm-256color")));
        Thread runnerThread = Thread.ofPlatform()
                .name("phase-three-terminal-smoke")
                .start(() -> {
                    try {
                        runner.run(
                                workspace,
                                configuration,
                                io.haifa.agent.application.coding.terminal.application.CodingTerminalStartup.empty(),
                                new PrintStream(applicationOutput),
                                ignored -> {});
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
        CountDownLatch finishQueuedRun = new CountDownLatch(1);
        AtomicInteger modelCalls = new AtomicInteger();
        var model = (io.haifa.agent.model.api.AgentChatModel) request -> {
            int call = modelCalls.incrementAndGet();
            if (call == 1) await(finishFirstRun);
            if (call == 3) await(finishQueuedRun);
            return call % 2 == 1 ? readWorkspace("restart-read-" + call, workspace) : response("answer-" + call);
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
            assertThat(client.findOutcome(firstRunId)).isPresent();
        }

        AgentRunId dispatchedRunId;
        try (LocalCodingAgent second = agent(workspace, configuration, model)) {
            LocalCodingSessionClient client = client(second);
            try {
                var listed = client.list(second.projectId(), 10);
                assertThat(listed).extracting(value -> value.sessionId()).containsExactly(sessionId);
                awaitCondition(
                        () -> client.open(sessionId)
                                .activeRun()
                                .map(activeRun -> !activeRun.runId().equals(firstRunId))
                                .orElse(false),
                        "queued follow-up dispatch");
                var reopened = client.open(sessionId);
                dispatchedRunId = reopened.activeRun().orElseThrow().runId();
                assertThat(reopened.eventCursor()).isEmpty();
                assertThat(client.restorableMessages(sessionId, 10)).isEmpty();
                assertThat(client.history(sessionId, 100).items())
                        .extracting(value -> value.body())
                        .contains("first task", "answer-2", "queued task");

                finishQueuedRun.countDown();
                awaitTerminal(second, dispatchedRunId);
                assertThat(client.reconcile(sessionId).activeRun()).isEmpty();
                assertThat(client.events(firstRunId, RunEventCursor.beforeFirst(firstRunId), 200)
                                .items())
                        .extracting(value -> value.eventId())
                        .doesNotHaveDuplicates();
            } finally {
                finishQueuedRun.countDown();
            }
        }

        try (LocalCodingAgent wrongWorkspace = agent(otherWorkspace, configuration, model)) {
            LocalCodingSessionClient client = client(wrongWorkspace);
            assertThat(client.list(wrongWorkspace.projectId(), 10)).isEmpty();
            assertThatThrownBy(() -> client.open(sessionId)).hasMessageContaining("Session is unavailable");
            assertThatThrownBy(() -> client.history(sessionId, 100)).hasMessageContaining("Session is unavailable");
            assertThatThrownBy(() -> client.events(firstRunId, RunEventCursor.beforeFirst(firstRunId), 10))
                    .hasMessageContaining("Session is unavailable");
            assertThatThrownBy(() -> client.findOutcome(firstRunId)).hasMessageContaining("Session is unavailable");
        }
        assertThat(modelCalls).hasValue(4);
    }

    @Test
    void twoSessionsOfOneProjectAdvanceConcurrentlyWithIndependentDurableEvidence() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("parallel-sessions"));
        Path alphaDirectory = Files.createDirectory(workspace.resolve("alpha"));
        Path betaDirectory = Files.createDirectory(workspace.resolve("beta"));
        Files.writeString(alphaDirectory.resolve("alpha-marker.txt"), "alpha fixture");
        Files.writeString(betaDirectory.resolve("beta-marker.txt"), "beta fixture");
        Path database = root.resolve("parallel-sessions.db");
        CliConfiguration configuration = sqliteConfiguration(database);

        ConcurrentHashMap<AgentRunId, AtomicInteger> modelCalls = new ConcurrentHashMap<>();
        ConcurrentHashMap<AgentRunId, List<String>> secondCallToolPaths = new ConcurrentHashMap<>();
        CountDownLatch bothFirstCallsInFlight = new CountDownLatch(2);
        CountDownLatch releaseFirstCalls = new CountDownLatch(1);
        AtomicInteger totalModelCalls = new AtomicInteger();
        var model = (io.haifa.agent.model.api.AgentChatModel) request -> {
            int call = modelCalls
                    .computeIfAbsent(request.runId(), ignored -> new AtomicInteger())
                    .incrementAndGet();
            totalModelCalls.incrementAndGet();
            boolean alpha = isAlphaRequest(request);
            if (call == 1) {
                bothFirstCallsInFlight.countDown();
                await(releaseFirstCalls);
                return toolResponse(
                        "parallel-read-" + request.runId().value(),
                        "file_list",
                        Map.of("path", (alpha ? alphaDirectory : betaDirectory).toString()));
            }
            secondCallToolPaths.put(request.runId(), toolResultPaths(request));
            return response(alpha ? "alpha-answer" : "beta-answer");
        };

        AgentSessionId sessionA;
        AgentSessionId sessionB;
        AgentRunId runA;
        AgentRunId runB;
        try (LocalCodingAgent agent = agent(workspace, configuration, model)) {
            LocalCodingSessionClient client = client(agent);
            var createdA = client.create(agent.projectId(), "alpha task", "parallel-alpha");
            sessionA = createdA.summary().sessionId();
            runA = createdA.activeRun().orElseThrow().runId();
            assertThat(createdA.summary().projectId()).isEqualTo(agent.projectId());
            assertThat(client.open(sessionA).summary().activeRunId()).contains(runA);

            var createdB = client.create(agent.projectId(), "beta task", "parallel-beta");
            sessionB = createdB.summary().sessionId();
            runB = createdB.activeRun().orElseThrow().runId();
            assertThat(createdB.summary().projectId()).isEqualTo(agent.projectId());
            assertThat(sessionB).isNotEqualTo(sessionA);
            assertThat(runB).isNotEqualTo(runA);
            assertThat(client.open(sessionB).summary().activeRunId()).contains(runB);

            try {
                assertThat(bothFirstCallsInFlight.await(10, TimeUnit.SECONDS))
                        .as("both Coding Sessions must enter their first model call before release")
                        .isTrue();
                assertThat(client.list(agent.projectId(), 10))
                        .filteredOn(summary -> summary.sessionId().equals(sessionA))
                        .singleElement()
                        .satisfies(summary -> assertThat(summary.activeRunId()).contains(runA));
                assertThat(client.list(agent.projectId(), 10))
                        .filteredOn(summary -> summary.sessionId().equals(sessionB))
                        .singleElement()
                        .satisfies(summary -> assertThat(summary.activeRunId()).contains(runB));
            } finally {
                releaseFirstCalls.countDown();
            }

            awaitTerminal(agent, runA);
            awaitTerminal(agent, runB);

            var eventsA =
                    client.events(runA, RunEventCursor.beforeFirst(runA), 1_000).items();
            var eventsB =
                    client.events(runB, RunEventCursor.beforeFirst(runB), 1_000).items();
            assertRunScoped(eventsA, runA, sessionA);
            assertRunScoped(eventsB, runB, sessionB);
            Set<String> toolCallsA = toolCallIds(eventsA, runA, sessionA);
            Set<String> toolCallsB = toolCallIds(eventsB, runB, sessionB);
            assertThat(toolCallsA).isNotEmpty();
            assertThat(toolCallsB).isNotEmpty();
            assertThat(toolCallsA).doesNotContainAnyElementsOf(toolCallsB);

            assertThat(client.history(sessionA, 100).items())
                    .extracting(value -> value.body())
                    .contains("alpha-answer")
                    .doesNotContain("beta-answer");
            assertThat(client.history(sessionB, 100).items())
                    .extracting(value -> value.body())
                    .contains("beta-answer")
                    .doesNotContain("alpha-answer");

            assertThat(secondCallToolPaths.keySet()).containsExactlyInAnyOrder(runA, runB);
            assertThat(secondCallToolPaths.get(runA))
                    .anyMatch(path -> path.endsWith("alpha-marker.txt"))
                    .noneMatch(path -> path.endsWith("beta-marker.txt"));
            assertThat(secondCallToolPaths.get(runB))
                    .anyMatch(path -> path.endsWith("beta-marker.txt"))
                    .noneMatch(path -> path.endsWith("alpha-marker.txt"));

            assertThat(modelCalls.get(runA)).hasValue(2);
            assertThat(modelCalls.get(runB)).hasValue(2);
            assertThat(client.list(agent.projectId(), 10))
                    .filteredOn(summary -> summary.sessionId().equals(sessionA)
                            || summary.sessionId().equals(sessionB))
                    .hasSize(2)
                    .allSatisfy(summary -> assertThat(summary.activeRunId()).isEmpty());
        }
        assertThat(Files.isRegularFile(database)).isTrue();
        assertThat(totalModelCalls).hasValue(4);
    }

    private static boolean isAlphaRequest(AgentChatRequest request) {
        return request.messages().stream()
                .filter(message -> message.role() == ModelMessageRole.USER)
                .anyMatch(message -> message.content().contains("alpha task"));
    }

    private static List<String> toolResultPaths(AgentChatRequest request) {
        List<String> paths = new ArrayList<>();
        for (ModelMessage message : request.messages()) {
            if (message.role() != ModelMessageRole.TOOL) continue;
            if (!(message.toolResultData().get("entries") instanceof List<?> entries)) continue;
            for (Object entry : entries) {
                if (entry instanceof Map<?, ?> values && values.get("path") instanceof String path) {
                    paths.add(path);
                }
            }
        }
        return paths;
    }

    private static void assertRunScoped(List<AgentRunEvent> events, AgentRunId runId, AgentSessionId sessionId) {
        assertThat(events).isNotEmpty().allSatisfy(event -> {
            assertThat(event.runId()).isEqualTo(runId);
            assertThat(event.sessionId()).isEqualTo(sessionId);
        });
    }

    private static Set<String> toolCallIds(List<AgentRunEvent> events, AgentRunId runId, AgentSessionId sessionId) {
        Set<String> ids = new LinkedHashSet<>();
        for (AgentRunEvent event : events) {
            if (!(event.payload() instanceof RunEventPayloads.ToolLifecycle tool)) continue;
            assertThat(event.runId()).isEqualTo(runId);
            assertThat(event.sessionId()).isEqualTo(sessionId);
            ids.add(tool.toolCallId());
        }
        return ids;
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
                agent.projectId(),
                agent.codingSessions(),
                agent.sessionHistory(),
                agent.runtime(),
                agent.identifiers(),
                agent.time(),
                List::of,
                agent::loadedResources,
                agent::reloadResources,
                agent.shell(),
                agent.exporter(),
                agent.outcomes(),
                agent::workspaceViews,
                agent::revokeWorkspace);
    }

    private static io.haifa.agent.runtime.api.RunEventPage awaitEvents(
            LocalCodingSessionClient client, AgentRunId runId) throws InterruptedException {
        awaitCondition(
                () -> !client.events(runId, RunEventCursor.beforeFirst(runId), 200)
                        .items()
                        .isEmpty(),
                "events for run " + runId);
        var page = client.events(runId, RunEventCursor.beforeFirst(runId), 200);
        assertThat(page.items()).isNotEmpty();
        return page;
    }

    private static void awaitTerminal(LocalCodingAgent agent, AgentRunId runId) throws InterruptedException {
        LocalCodingSessionClient client = client(agent);
        awaitCondition(() -> client.findRun(runId).orElseThrow().status().isTerminal(), "terminal run " + runId);
        var snapshot = client.findRun(runId).orElseThrow();
        assertThat(snapshot.status()).isEqualTo(AgentRunStatus.COMPLETED);
    }

    private static void awaitNoActiveRun(LocalCodingAgent agent) throws InterruptedException {
        LocalCodingSessionClient client = client(agent);
        awaitCondition(
                () -> {
                    var sessions = client.list(agent.projectId(), 10);
                    return !sessions.isEmpty()
                            && client.open(sessions.getFirst().sessionId())
                                    .activeRun()
                                    .isEmpty();
                },
                "terminal session to reach an authoritative terminal state");
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

    private static AgentChatResponse readWorkspace(String id, Path workspace) {
        return toolResponse(
                id,
                "file_list",
                Map.of("path", workspace.toAbsolutePath().normalize().toString()));
    }

    private static AgentChatResponse toolResponse(String id, String name, Map<String, Object> arguments) {
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

    private static CliConfiguration sqliteConfiguration(Path database) {
        return configuration(ProjectPersistenceConfiguration.sqlite(database, "env://HAIFA_TEST_CONTINUATION_KEY"));
    }

    private static CliConfiguration memoryConfiguration() {
        return configuration(ProjectPersistenceConfiguration.memory());
    }

    private static CliConfiguration configuration(ProjectPersistenceConfiguration persistence) {
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
                .persistence(persistence)
                .build();
    }

    private static AesGcmModelContinuationProtector protector() {
        return new AesGcmModelContinuationProtector(
                new SecretKeySpec(new byte[32], "AES"), new java.security.SecureRandom());
    }
}
