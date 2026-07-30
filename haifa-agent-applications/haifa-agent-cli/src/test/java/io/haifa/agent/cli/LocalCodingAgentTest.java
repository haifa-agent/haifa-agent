package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.application.project.persistence.ProjectPersistenceConfiguration;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.runtime.api.InteractionResponse;
import io.haifa.agent.runtime.api.InteractionResponseId;
import io.haifa.agent.runtime.api.InteractionResponseType;
import io.haifa.agent.runtime.core.model.continuation.AesGcmModelContinuationProtector;
import io.haifa.agent.skill.api.SkillOrigin;
import io.haifa.agent.skill.api.SkillParserMode;
import io.haifa.agent.store.jsonl.JsonlTranscriptReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalCodingAgentTest {
    @TempDir
    Path workspace;

    @TempDir
    Path configuredSkillRoot;

    @Test
    void insecureModelOptInIsRestrictedToExplicitLoopbackHttp() {
        CliConfiguration defaults = CliConfiguration.defaults();
        CliConfiguration loopback = withModelEndpoint(defaults, URI.create("http://127.0.0.1:18080"));
        CliConfiguration external = withModelEndpoint(defaults, URI.create("http://example.com"));

        assertThat(LocalCodingAgent.allowInsecureLoopback(loopback, null)).isFalse();
        assertThat(LocalCodingAgent.allowInsecureLoopback(loopback, "false")).isFalse();
        assertThat(LocalCodingAgent.allowInsecureLoopback(loopback, " true ")).isTrue();
        assertThat(LocalCodingAgent.allowInsecureLoopback(defaults, "true")).isFalse();
        assertThatThrownBy(() -> LocalCodingAgent.allowInsecureLoopback(external, "true"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only an HTTP loopback model endpoint");
    }

    @Test
    void windowsDefaultLocalNativeFailsClosedBeforeModelInvocation() {
        org.junit.jupiter.api.Assumptions.assumeTrue(isWindows());
        var model = (io.haifa.agent.model.api.AgentChatModel) request -> {
            throw new AssertionError("unsupported provider must fail before model invocation");
        };

        assertThatThrownBy(() -> LocalCodingAgent.create(
                        workspace,
                        CliConfiguration.defaults(),
                        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                        model))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SANDBOX_ADAPTER_UNAVAILABLE")
                .hasMessageContaining("host-guarded");
    }

    @Test
    void denyRemovesExecutionBeforeToolCatalogDisclosure() {
        CliConfiguration defaults = CliConfiguration.defaults();
        var denied = new CliConfiguration(
                defaults.model(),
                defaults.enabledTools(),
                defaults.mcpServers(),
                hostExecution(defaults.execution()),
                ApprovalMode.DENY,
                defaults.timeout(),
                defaults.maxIterations(),
                defaults.maxToolCalls());

        assertThat(LocalCodingAgent.effectiveBuiltInTools(denied)).doesNotContain("execution.run");
        assertThat(LocalCodingAgent.effectiveBuiltInTools(defaults)).contains("execution.run");
    }

    @Test
    void sqliteWithJsonlPersistsCliRunAndReleasesDatabaseOnClose() throws Exception {
        Path database = configuredSkillRoot.resolve("cli-runtime.db");
        Path transcripts = Files.createDirectory(configuredSkillRoot.resolve("transcripts"));
        CliConfiguration defaults = CliConfiguration.defaults();
        var configuration = new CliConfiguration(
                defaults.model(),
                defaults.enabledTools(),
                defaults.mcpServers(),
                defaults.web(),
                defaults.skills(),
                hostExecution(defaults.execution()),
                defaults.approval(),
                defaults.timeout(),
                defaults.maxIterations(),
                defaults.maxToolCalls(),
                ProjectPersistenceConfiguration.sqliteWithJsonl(
                        database, transcripts, "env://HAIFA_TEST_CONTINUATION_KEY"));
        AtomicInteger modelCalls = new AtomicInteger();
        var model = (io.haifa.agent.model.api.AgentChatModel) request -> {
            int call = modelCalls.incrementAndGet();
            return call % 2 == 1
                    ? toolResponse("cli-persistence-read-" + call, "file_list", Map.of("path", "."))
                    : answer("cli-persistence-" + call, "persisted");
        };
        io.haifa.agent.core.run.AgentRunId runId;

        try (var agent = LocalCodingAgent.create(
                workspace,
                configuration,
                new PrintStream(new ByteArrayOutputStream()),
                model,
                ignored -> {},
                new AesGcmModelContinuationProtector(
                        new SecretKeySpec(new byte[32], "AES"), new java.security.SecureRandom()))) {
            var accepted = agent.start("Persist this run.");
            runId = accepted.runId();
            Instant deadline = now().plusSeconds(10);
            var snapshot = agent.runtime().find(runId).orElseThrow();
            while (!snapshot.status().isTerminal() && now().isBefore(deadline)) {
                Thread.sleep(25);
                snapshot = agent.runtime().find(runId).orElseThrow();
            }
            assertThat(snapshot.status()).as("final snapshot: %s", snapshot).isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(snapshot.output()).contains("persisted");
        }

        assertThat(new JsonlTranscriptReader(transcripts).read(runId.value()).events())
                .extracting(event -> event.eventType())
                .contains("run.created", "run.completed");
        try (var reopened = LocalCodingAgent.create(
                workspace,
                configuration,
                new PrintStream(new ByteArrayOutputStream()),
                model,
                ignored -> {},
                new AesGcmModelContinuationProtector(
                        new SecretKeySpec(new byte[32], "AES"), new java.security.SecureRandom()))) {
            assertThat(reopened.executionSettled(runId)).isTrue();
            var second = reopened.start("Persist a second run.");
            Instant deadline = now().plusSeconds(10);
            var snapshot = reopened.runtime().find(second.runId()).orElseThrow();
            while (!snapshot.status().isTerminal() && now().isBefore(deadline)) {
                Thread.sleep(25);
                snapshot = reopened.runtime().find(second.runId()).orElseThrow();
            }
            assertThat(snapshot.status()).isEqualTo(AgentRunStatus.COMPLETED);
        }
        assertThat(Files.deleteIfExists(database)).isTrue();
    }

    @Test
    void sqliteAskPersistsApprovalAndContinuesAfterHumanRejection() throws Exception {
        Path database = configuredSkillRoot.resolve("approval-runtime.db");
        Path transcripts = Files.createDirectory(configuredSkillRoot.resolve("approval-transcripts"));
        CliConfiguration defaults = CliConfiguration.defaults();
        var configuration = new CliConfiguration(
                defaults.model(),
                defaults.enabledTools(),
                defaults.mcpServers(),
                defaults.web(),
                defaults.skills(),
                hostExecution(defaults.execution()),
                ApprovalMode.ASK,
                Duration.ofSeconds(15),
                defaults.maxIterations(),
                defaults.maxToolCalls(),
                ProjectPersistenceConfiguration.sqliteWithJsonl(
                        database, transcripts, "env://HAIFA_TEST_CONTINUATION_KEY"));
        AtomicInteger calls = new AtomicInteger();
        var traces = new CopyOnWriteArrayList<io.haifa.agent.runtime.core.trace.RuntimeTraceEvent>();
        io.haifa.agent.core.run.AgentRunId runId;
        var model = (io.haifa.agent.model.api.AgentChatModel) request -> {
            if (calls.incrementAndGet() == 1) {
                return new AgentChatResponse(
                        "approval-model-1",
                        "stub-model",
                        "",
                        List.of(new ModelToolCall(
                                new ProviderToolCallCorrelationId("approval-tool-call-1"),
                                "file_write",
                                Map.of("path", "must-not-exist.txt", "content", "rejected"))),
                        ModelFinishReason.TOOL_CALLS,
                        ModelUsage.unpriced(5, 2),
                        "stub",
                        Map.of());
            }
            assertThat(request.messages())
                    .withFailMessage("second model request messages: %s", request.messages())
                    .anyMatch(message -> message.role() == ModelMessageRole.TOOL
                            && message.providerCorrelationId()
                                    .orElseThrow()
                                    .value()
                                    .equals("approval-tool-call-1"));
            return new AgentChatResponse(
                    "approval-model-2",
                    "stub-model",
                    "rejection respected",
                    List.of(),
                    ModelFinishReason.STOP,
                    ModelUsage.unpriced(5, 2),
                    "stub",
                    Map.of());
        };

        try (var agent = LocalCodingAgent.create(
                workspace,
                configuration,
                new PrintStream(new ByteArrayOutputStream()),
                model,
                traces::add,
                new AesGcmModelContinuationProtector(
                        new SecretKeySpec(new byte[32], "AES"), new java.security.SecureRandom()))) {
            var accepted = agent.start("Try a write and honor a rejection.");
            runId = accepted.runId();
            Instant pendingDeadline = now().plusSeconds(10);
            var pending = agent.interactions().pending(accepted.runId());
            while (pending.isEmpty() && now().isBefore(pendingDeadline)) {
                Thread.sleep(25);
                pending = agent.interactions().pending(accepted.runId());
            }
            assertThat(pending).isPresent();
            assertThat(agent.runtime().find(accepted.runId()).orElseThrow().status())
                    .isEqualTo(AgentRunStatus.WAITING_APPROVAL);
            while (!agent.executionSettled(accepted.runId()) && now().isBefore(pendingDeadline)) {
                Thread.sleep(25);
            }
            assertThat(agent.executionSettled(accepted.runId())).isTrue();

            var request = pending.orElseThrow();
            agent.runtime()
                    .respond(new InteractionResponse(
                            new InteractionResponseId(agent.identifiers().nextValue()),
                            request.id(),
                            request.runId(),
                            InteractionResponseType.REJECT,
                            List.of(),
                            "test-reject-" + request.id().value(),
                            agent.time().now()));

            Instant completionDeadline = now().plusSeconds(10);
            var completed = agent.runtime().find(accepted.runId()).orElseThrow();
            while (!completed.status().isTerminal() && now().isBefore(completionDeadline)) {
                Thread.sleep(25);
                completed = agent.runtime().find(accepted.runId()).orElseThrow();
            }
            assertThat(completed.status()).isEqualTo(AgentRunStatus.FAILED);
            assertThat(completed.error().orElseThrow().code().value()).isEqualTo("COMPLETION_REPAIR_EXHAUSTED");
        }

        assertThat(workspace.resolve("must-not-exist.txt")).doesNotExist();
        assertThat(calls).hasValue(4);
        assertThat(traces).noneMatch(event -> event.operation().equals("runtime.error"));
        assertThat(new JsonlTranscriptReader(transcripts).read(runId.value()).events())
                .extracting(event -> event.eventType())
                .contains(
                        "policy.decision.made",
                        "approval.requested",
                        "approval.authority.verified",
                        "approval.target.validated",
                        "approval.responded",
                        "run.failed");
    }

    @Test
    void sqliteStartupFailsClosedWhenStableProtectorSecretIsUnavailable() {
        CliConfiguration defaults = CliConfiguration.defaults();
        var configuration = new CliConfiguration(
                defaults.model(),
                defaults.enabledTools(),
                defaults.mcpServers(),
                defaults.web(),
                defaults.skills(),
                hostExecution(defaults.execution()),
                defaults.approval(),
                defaults.timeout(),
                defaults.maxIterations(),
                defaults.maxToolCalls(),
                ProjectPersistenceConfiguration.sqlite(
                        configuredSkillRoot.resolve("unopened.db"),
                        "env://HAIFA_TEST_SECRET_THAT_MUST_NOT_EXIST_7E8297"));

        assertThatThrownBy(() -> LocalCodingAgent.create(
                        workspace, configuration, new PrintStream(new ByteArrayOutputStream()), request -> {
                            throw new AssertionError("model must not run");
                        }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("durable continuation protector secret is unavailable");
        assertThat(configuredSkillRoot.resolve("unopened.db")).doesNotExist();
    }

    @Test
    void stubModelRunsGeneralShellThroughTheCliAssembly() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String command =
                isWindows() ? "Set-Content -NoNewline -Path shell-e2e.txt -Value stub" : "printf stub > shell-e2e.txt";
        var model = (io.haifa.agent.model.api.AgentChatModel) request -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                assertThat(request.tools())
                        .extracting(io.haifa.agent.model.api.ModelToolSpecification::name)
                        .contains("execution_run", "skill_load", "skill_resource_read");
                return toolResponse(
                        "shell-call-1",
                        "execution_run",
                        Map.of(
                                "command",
                                command,
                                "workdir",
                                ".",
                                "timeoutMillis",
                                5000,
                                "description",
                                "Write a test file",
                                "operationFamily",
                                "MUTATE"));
            }
            if (call == 2) {
                assertThat(request.messages())
                        .anyMatch(message -> message.role() == ModelMessageRole.TOOL
                                && "SUCCEEDED".equals(message.toolResultData().get("status"))
                                && message.toolResultData().containsKey("fileChangeSetId"));
                return toolResponse(
                        "shell-test",
                        "execution_run",
                        Map.of(
                                "command",
                                fileExistsCommand("shell-e2e.txt"),
                                "workdir",
                                ".",
                                "timeoutMillis",
                                5000,
                                "description",
                                "Validate shell output",
                                "operationFamily",
                                "TEST"));
            }
            if (call == 3) {
                return toolResponse(
                        "shell-diff",
                        "execution_run",
                        Map.of(
                                "command",
                                fileExistsCommand("shell-e2e.txt"),
                                "workdir",
                                ".",
                                "timeoutMillis",
                                5000,
                                "description",
                                "Inspect shell diff",
                                "operationFamily",
                                "DIFF"));
            }
            return answer("cli-shell-complete", "shell complete");
        };
        CliConfiguration defaults = CliConfiguration.defaults();
        var automatic = new CliConfiguration(
                defaults.model(),
                defaults.enabledTools(),
                defaults.mcpServers(),
                hostExecution(defaults.execution()),
                ApprovalMode.AUTO,
                Duration.ofSeconds(15),
                defaults.maxIterations(),
                defaults.maxToolCalls());
        var renderedOutput = new ByteArrayOutputStream();

        try (var agent = LocalCodingAgent.create(
                workspace, automatic, new PrintStream(renderedOutput, true, StandardCharsets.UTF_8), model)) {
            var accepted = agent.start("Write the representative file with the general shell tool.");
            Instant deadline = now().plusSeconds(10);
            var snapshot = agent.runtime().find(accepted.runId()).orElseThrow();
            while (!snapshot.status().isTerminal() && now().isBefore(deadline)) {
                Thread.sleep(25);
                snapshot = agent.runtime().find(accepted.runId()).orElseThrow();
            }

            assertThat(snapshot.status())
                    .withFailMessage("run failed: %s", snapshot.error())
                    .isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(snapshot.output()).contains("shell complete");
        }
        assertThat(Files.readString(workspace.resolve("shell-e2e.txt"))).isEqualTo("stub");
        assertThat(calls).hasValue(4);
    }

    @Test
    void stubModelCannotFinishChangeUntilDeliveryEvidenceExistsAndRepairsRemainBounded() throws Exception {
        Path successfulWorkspace = Files.createDirectory(workspace.resolve("delivery-success"));
        Path failedWorkspace = Files.createDirectory(workspace.resolve("delivery-failure"));
        AtomicInteger successfulCalls = new AtomicInteger();
        var successfulModel = (io.haifa.agent.model.api.AgentChatModel) request -> {
            int call = successfulCalls.incrementAndGet();
            return switch (call) {
                case 1 -> {
                    assertThat(request.messages())
                            .anyMatch(message -> message.role() == ModelMessageRole.SYSTEM
                                    && message.content().contains("existing tests, test scripts, and fixtures")
                                    && message.content().contains("map every stated acceptance clause"));
                    yield answer("delivery-premature", "premature final");
                }
                case 2 -> {
                    yield toolResponse(
                            "delivery-write", "file_create", Map.of("path", "delivered.txt", "content", "delivered\n"));
                }
                case 3 ->
                    toolResponse(
                            "delivery-test",
                            "execution_run",
                            Map.of(
                                    "command",
                                    fileExistsCommand("delivered.txt"),
                                    "workdir",
                                    ".",
                                    "timeoutMillis",
                                    5_000,
                                    "description",
                                    "Validate delivered file",
                                    "operationFamily",
                                    "TEST"));
                case 4 ->
                    toolResponse(
                            "delivery-diff",
                            "execution_run",
                            Map.of(
                                    "command",
                                    fileExistsCommand("delivered.txt"),
                                    "workdir",
                                    ".",
                                    "timeoutMillis",
                                    5_000,
                                    "description",
                                    "Inspect delivered change",
                                    "operationFamily",
                                    "DIFF"));
                default -> answer("delivery-complete", "delivery complete");
            };
        };
        CliConfiguration configuration = automaticHostConfiguration();

        try (var agent = LocalCodingAgent.create(
                successfulWorkspace,
                configuration,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                successfulModel)) {
            var accepted = agent.start("fix the implementation by creating the requested delivery file");
            var snapshot = awaitTerminal(agent, accepted.runId());
            assertThat(snapshot.status())
                    .withFailMessage(
                            "run did not complete: snapshot=%s calls=%s pending=%s",
                            snapshot,
                            successfulCalls.get(),
                            agent.interactions().pending(accepted.runId()))
                    .isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(snapshot.output()).contains("delivery complete");
        }
        assertThat(Files.readString(successfulWorkspace.resolve("delivered.txt")))
                .isEqualTo("delivered\n");
        assertThat(successfulCalls).hasValue(5);

        AtomicInteger failedCalls = new AtomicInteger();
        var failedModel = (io.haifa.agent.model.api.AgentChatModel) request -> {
            failedCalls.incrementAndGet();
            return answer("delivery-still-premature-" + failedCalls.get(), "still premature");
        };
        try (var agent = LocalCodingAgent.create(
                failedWorkspace,
                configuration,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                failedModel)) {
            var accepted = agent.start("fix the implementation but do not provide delivery evidence");
            var snapshot = awaitTerminal(agent, accepted.runId());
            assertThat(snapshot.status()).isEqualTo(AgentRunStatus.FAILED);
            assertThat(snapshot.error().orElseThrow().code().value()).isEqualTo("COMPLETION_REPAIR_EXHAUSTED");
        }
        assertThat(failedCalls).hasValue(3);
    }

    @Test
    void stubAnalyzeRunUsesReadOnlyEvidenceWithoutRequiringWorkspaceChange() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "deterministic root cause evidence\n");
        AtomicInteger calls = new AtomicInteger();
        var model = (io.haifa.agent.model.api.AgentChatModel) request -> switch (calls.incrementAndGet()) {
            case 1 -> answer("analyze-premature", "premature analysis");
            case 2 -> toolResponse("analyze-read", "file_read", Map.of("path", "README.md"));
            default -> answer("analyze-complete", "root cause analyzed from deterministic read-only evidence");
        };

        try (var agent = LocalCodingAgent.create(
                workspace,
                trustedHostConfiguration(CliConfiguration.defaults()),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                model)) {
            var accepted = agent.start("analyze root cause and report evidence without changing files");
            var snapshot = awaitTerminal(agent, accepted.runId());
            assertThat(snapshot.status()).isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(snapshot.output().orElseThrow()).contains("root cause analyzed");
        }
        assertThat(calls).hasValue(3);
        assertThat(Files.readString(workspace.resolve("README.md"))).isEqualTo("deterministic root cause evidence\n");
    }

    @Test
    void stubModelActivatesBaseSkillThroughToolPipelineAndReceivesSkillPromptNextIteration() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var observedTraces = new CopyOnWriteArrayList<io.haifa.agent.runtime.core.trace.RuntimeTraceEvent>();
        var model = (io.haifa.agent.model.api.AgentChatModel) request -> {
            if (calls.incrementAndGet() == 1) {
                assertThat(request.messages())
                        .anyMatch(message -> message.role() == ModelMessageRole.SYSTEM
                                && message.content().contains("task-planning")
                                && message.content().contains("metadata-only"))
                        .noneMatch(message -> message.content().contains("# Task planning"));
                assertThat(request.tools())
                        .extracting(tool -> tool.name())
                        .contains("skill_load", "skill_resource_read");
                return new AgentChatResponse(
                        "cli-skill-1",
                        "stub-model",
                        "",
                        List.of(new ModelToolCall(
                                new ProviderToolCallCorrelationId("skill-call-1"),
                                "skill_load",
                                Map.of("skill", "task-planning", "reason", "the request has dependent stages"))),
                        ModelFinishReason.TOOL_CALLS,
                        ModelUsage.unpriced(10, 3),
                        "stub",
                        Map.of());
            }
            var toolResult = request.messages().stream()
                    .filter(message -> message.role() == ModelMessageRole.TOOL
                            && message.providerCorrelationId()
                                    .orElseThrow()
                                    .value()
                                    .equals("skill-call-1"))
                    .findFirst()
                    .orElseThrow();
            assertThat(toolResult.content())
                    .contains("Activated Skill task-planning")
                    .doesNotContain("# Task planning");
            assertThat(request.messages())
                    .anyMatch(message -> message.role() == ModelMessageRole.SYSTEM
                            && message.content().contains("# Task planning"));
            return new AgentChatResponse(
                    "cli-skill-2",
                    "stub-model",
                    "planned",
                    List.of(),
                    ModelFinishReason.STOP,
                    ModelUsage.unpriced(15, 4),
                    "stub",
                    Map.of());
        };

        try (var agent = LocalCodingAgent.create(
                workspace,
                trustedHostConfiguration(CliConfiguration.defaults()),
                new PrintStream(new ByteArrayOutputStream()),
                model,
                observedTraces::add)) {
            var accepted = agent.start("Plan and complete a dependent task.");
            Instant deadline = now().plusSeconds(10);
            var snapshot = agent.runtime().find(accepted.runId()).orElseThrow();
            while (!snapshot.status().isTerminal() && now().isBefore(deadline)) {
                Thread.sleep(25);
                snapshot = agent.runtime().find(accepted.runId()).orElseThrow();
            }
            assertThat(snapshot.status()).isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(snapshot.output()).contains("planned");
        }
        assertThat(calls).hasValue(2);
        assertThat(observedTraces)
                .anyMatch(event -> event.operation().equals("model.invoke"))
                .anyMatch(event -> event.operation().equals("tool.execute")
                        && event.safeAttributes().get("providerId").equals("haifa-runtime-skill"))
                .anyMatch(event -> event.operation().equals("tool.persisted"));
    }

    @Test
    void configuredLocalUserSkillIsDiscoveredAllowlistedAndActivatedThroughTheProductionAssembly() throws Exception {
        writeSkill(
                "local-procedure",
                "A local procedure for tasks that require an externally configured method.",
                "# Local procedure\n\nFollow the configured local method.");
        writeSkill(
                "not-allowed",
                "A local procedure that is intentionally excluded from the profile.",
                "# Not allowed\n\nThis content must not be disclosed.");
        CliConfiguration defaults = CliConfiguration.defaults();
        var skills = new CliConfiguration.Skills(
                Set.of("local-procedure"),
                List.of(new CliConfiguration.LocalSkillDirectory(
                        "personal", configuredSkillRoot, 100, SkillParserMode.STRICT, SkillOrigin.CREATED)));
        var configuration = new CliConfiguration(
                defaults.model(),
                defaults.enabledTools(),
                defaults.mcpServers(),
                defaults.web(),
                skills,
                hostExecution(defaults.execution()),
                defaults.approval(),
                defaults.timeout(),
                defaults.maxIterations(),
                defaults.maxToolCalls());
        AtomicInteger calls = new AtomicInteger();
        var model = (io.haifa.agent.model.api.AgentChatModel) request -> {
            if (calls.incrementAndGet() == 1) {
                assertThat(request.messages())
                        .anyMatch(message -> message.role() == ModelMessageRole.SYSTEM
                                && message.content().contains("local-procedure")
                                && message.content().contains("metadata-only"))
                        .noneMatch(message -> message.content().contains("# Local procedure"))
                        .noneMatch(message -> message.content().contains("not-allowed"))
                        .noneMatch(message -> message.content().contains(configuredSkillRoot.toString()));
                return new AgentChatResponse(
                        "cli-local-skill-1",
                        "stub-model",
                        "",
                        List.of(new ModelToolCall(
                                new ProviderToolCallCorrelationId("local-skill-call-1"),
                                "skill_load",
                                Map.of("skill", "local-procedure", "reason", "use the configured local method"))),
                        ModelFinishReason.TOOL_CALLS,
                        ModelUsage.unpriced(10, 3),
                        "stub",
                        Map.of());
            }
            assertThat(request.messages())
                    .anyMatch(message -> message.role() == ModelMessageRole.SYSTEM
                            && message.content().contains("# Local procedure"))
                    .noneMatch(message -> message.content().contains("# Not allowed"));
            return new AgentChatResponse(
                    "cli-local-skill-2",
                    "stub-model",
                    "local skill complete",
                    List.of(),
                    ModelFinishReason.STOP,
                    ModelUsage.unpriced(15, 4),
                    "stub",
                    Map.of());
        };

        try (var agent = LocalCodingAgent.create(
                workspace, configuration, new PrintStream(new ByteArrayOutputStream()), model)) {
            var accepted = agent.start("Use the configured local procedure.");
            Instant deadline = now().plusSeconds(10);
            var snapshot = agent.runtime().find(accepted.runId()).orElseThrow();
            while (!snapshot.status().isTerminal() && now().isBefore(deadline)) {
                Thread.sleep(25);
                snapshot = agent.runtime().find(accepted.runId()).orElseThrow();
            }
            assertThat(snapshot.status()).isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(snapshot.output()).contains("local skill complete");
        }
        assertThat(calls).hasValue(2);
    }

    @Test
    void emptySkillAllowlistOmitsSkillDisclosureAndTools() throws Exception {
        CliConfiguration defaults = CliConfiguration.defaults();
        var configuration = new CliConfiguration(
                defaults.model(),
                defaults.enabledTools(),
                defaults.mcpServers(),
                defaults.web(),
                new CliConfiguration.Skills(Set.of(), List.of()),
                hostExecution(defaults.execution()),
                defaults.approval(),
                defaults.timeout(),
                defaults.maxIterations(),
                defaults.maxToolCalls());
        AtomicInteger calls = new AtomicInteger();
        var model = (io.haifa.agent.model.api.AgentChatModel) request -> {
            int call = calls.incrementAndGet();
            assertThat(request.tools())
                    .extracting(io.haifa.agent.model.api.ModelToolSpecification::name)
                    .doesNotContain("skill_load", "skill_resource_read");
            assertThat(request.messages())
                    .noneMatch(message -> message.role() == ModelMessageRole.SYSTEM
                            && message.content().contains("Available Skills"));
            return call == 1
                    ? toolResponse("cli-no-skills-read", "file_list", Map.of("path", "."))
                    : answer("cli-no-skills", "complete");
        };

        try (var agent = LocalCodingAgent.create(
                workspace, configuration, new PrintStream(new ByteArrayOutputStream()), model)) {
            var accepted = agent.start("Complete without skills.");
            Instant deadline = now().plusSeconds(10);
            var snapshot = agent.runtime().find(accepted.runId()).orElseThrow();
            while (!snapshot.status().isTerminal() && now().isBefore(deadline)) {
                Thread.sleep(25);
                snapshot = agent.runtime().find(accepted.runId()).orElseThrow();
            }
            assertThat(snapshot.status()).isEqualTo(AgentRunStatus.COMPLETED);
        }
        assertThat(calls).hasValue(2);
    }

    @Test
    void localSkillDirectoryCannotOverlapWorkspaceFileToolRoot() throws Exception {
        Path nestedSkillRoot = Files.createDirectory(workspace.resolve("skills"));
        CliConfiguration defaults = CliConfiguration.defaults();
        var configuration = new CliConfiguration(
                defaults.model(),
                defaults.enabledTools(),
                defaults.mcpServers(),
                defaults.web(),
                new CliConfiguration.Skills(
                        Set.of("local-test"),
                        List.of(new CliConfiguration.LocalSkillDirectory(
                                "personal", nestedSkillRoot, 100, SkillParserMode.STRICT, SkillOrigin.CREATED))),
                hostExecution(defaults.execution()),
                defaults.approval(),
                defaults.timeout(),
                defaults.maxIterations(),
                defaults.maxToolCalls());

        assertThatThrownBy(() -> LocalCodingAgent.create(
                        workspace, configuration, new PrintStream(new ByteArrayOutputStream()), request -> {
                            throw new AssertionError("invalid assembly must fail before model invocation");
                        }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not overlap the CLI workspace");
    }

    @Test
    void fileListAcceptsTheDisclosedDotWorkspaceRoot() throws Exception {
        Files.writeString(workspace.resolve("visible.txt"), "fixture", StandardCharsets.UTF_8);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<AgentChatRequest> firstRequest = new AtomicReference<>();
        var model = (io.haifa.agent.model.api.AgentChatModel) request -> {
            if (calls.incrementAndGet() == 1) {
                firstRequest.set(request);
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
                trustedHostConfiguration(CliConfiguration.defaults()),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                model)) {
            var accepted = agent.start("List the workspace root.");
            Instant deadline = now().plusSeconds(10);
            var snapshot = agent.runtime().find(accepted.runId()).orElseThrow();
            while (!snapshot.status().isTerminal() && now().isBefore(deadline)) {
                Thread.sleep(25);
                snapshot = agent.runtime().find(accepted.runId()).orElseThrow();
            }

            assertThat(snapshot.status()).isEqualTo(AgentRunStatus.COMPLETED);
        }
        assertThat(calls).hasValue(2);
        assertThat(firstRequest.get().messages().stream()
                        .filter(message -> message.role() == ModelMessageRole.USER)
                        .toList())
                .singleElement()
                .satisfies(message -> assertThat(message.content()).isEqualTo("List the workspace root."));
    }

    @Test
    void missingFileReadIsReturnedToTheModelAsARecoverableToolFailure() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var model = (io.haifa.agent.model.api.AgentChatModel) request -> {
            if (calls.incrementAndGet() == 1) {
                return new AgentChatResponse(
                        "cli-missing-1",
                        "stub-model",
                        "",
                        List.of(new ModelToolCall(
                                new ProviderToolCallCorrelationId("missing-call-1"),
                                "file_read",
                                Map.of("path", "missing.txt"))),
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
                                    .equals("missing-call-1")
                            && message.content().contains("PATH_NOT_FOUND"));
            return new AgentChatResponse(
                    "cli-missing-2",
                    "stub-model",
                    "recovered",
                    List.of(),
                    ModelFinishReason.STOP,
                    ModelUsage.unpriced(15, 4),
                    "stub",
                    Map.of());
        };

        try (var agent = LocalCodingAgent.create(
                workspace,
                trustedHostConfiguration(CliConfiguration.defaults()),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                model)) {
            var accepted = agent.start("Inspect a missing file and recover.");
            Instant deadline = now().plusSeconds(10);
            var snapshot = agent.runtime().find(accepted.runId()).orElseThrow();
            while (!snapshot.status().isTerminal() && now().isBefore(deadline)) {
                Thread.sleep(25);
                snapshot = agent.runtime().find(accepted.runId()).orElseThrow();
            }

            assertThat(snapshot.status()).isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(snapshot.output()).contains("recovered");
        }
        assertThat(calls).hasValue(2);
    }

    private static AgentChatResponse answer(String id, String text) {
        return new AgentChatResponse(
                id, "stub-model", text, List.of(), ModelFinishReason.STOP, ModelUsage.unpriced(5, 2), "stub", Map.of());
    }

    private static AgentChatResponse toolResponse(String id, String tool, Map<String, Object> arguments) {
        return new AgentChatResponse(
                id,
                "stub-model",
                "",
                List.of(new ModelToolCall(new ProviderToolCallCorrelationId(id + "-call"), tool, arguments)),
                ModelFinishReason.TOOL_CALLS,
                ModelUsage.unpriced(5, 2),
                "stub",
                Map.of());
    }

    private static String fileExistsCommand(String file) {
        return isWindows() ? "if (Test-Path '" + file + "') { exit 0 } else { exit 1 }" : "test -f '" + file + "'";
    }

    private static CliConfiguration automaticHostConfiguration() {
        CliConfiguration trusted = trustedHostConfiguration(CliConfiguration.defaults());
        return new CliConfiguration(
                trusted.model(),
                trusted.enabledTools(),
                trusted.mcpServers(),
                trusted.web(),
                trusted.skills(),
                trusted.execution(),
                ApprovalMode.AUTO,
                trusted.timeout(),
                trusted.maxIterations(),
                trusted.maxToolCalls(),
                trusted.persistence());
    }

    private static io.haifa.agent.runtime.api.AgentRunSnapshot awaitTerminal(
            LocalCodingAgent agent, io.haifa.agent.core.run.AgentRunId runId) throws InterruptedException {
        Instant deadline = now().plusSeconds(30);
        var snapshot = agent.runtime().find(runId).orElseThrow();
        while (!snapshot.status().isTerminal() && now().isBefore(deadline)) {
            Thread.sleep(25);
            snapshot = agent.runtime().find(runId).orElseThrow();
        }
        return snapshot;
    }

    private static CliConfiguration trustedHostConfiguration(CliConfiguration configuration) {
        return new CliConfiguration(
                configuration.model(),
                configuration.enabledTools(),
                configuration.mcpServers(),
                configuration.web(),
                configuration.skills(),
                hostExecution(configuration.execution()),
                configuration.approval(),
                configuration.timeout(),
                configuration.maxIterations(),
                configuration.maxToolCalls(),
                configuration.persistence());
    }

    private static CliConfiguration withModelEndpoint(CliConfiguration configuration, URI endpoint) {
        CliConfiguration.Model model = configuration.model();
        return new CliConfiguration(
                new CliConfiguration.Model(
                        model.providerId(),
                        model.modelId(),
                        endpoint,
                        model.credentialRef(),
                        model.workspaceId(),
                        model.region()),
                configuration.enabledTools(),
                configuration.mcpServers(),
                configuration.web(),
                configuration.skills(),
                configuration.execution(),
                configuration.approval(),
                configuration.timeout(),
                configuration.maxIterations(),
                configuration.maxToolCalls(),
                configuration.persistence());
    }

    private static CliConfiguration.Execution hostExecution(CliConfiguration.Execution execution) {
        return new CliConfiguration.Execution(
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
                List.of());
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win");
    }

    private static Instant now() {
        return Instant.ofEpochMilli(System.currentTimeMillis());
    }

    private void writeSkill(String name, String description, String instructions) throws Exception {
        Path packageRoot = Files.createDirectory(configuredSkillRoot.resolve(name));
        Files.writeString(
                packageRoot.resolve("SKILL.md"),
                """
                ---
                name: %s
                description: %s
                ---
                %s
                """
                        .formatted(name, description, instructions),
                StandardCharsets.UTF_8);
    }
}
