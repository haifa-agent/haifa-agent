package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.application.project.persistence.ProjectPersistenceConfiguration;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
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
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalCodingAgentTest {
    @TempDir
    Path workspace;

    @TempDir
    Path configuredSkillRoot;

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
        var model = (io.haifa.agent.model.api.AgentChatModel) request -> new AgentChatResponse(
                "cli-persistence",
                "stub-model",
                "persisted",
                List.of(),
                ModelFinishReason.STOP,
                ModelUsage.unpriced(5, 2),
                "stub",
                Map.of());
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
            assertThat(completed.status()).isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(completed.output()).contains("rejection respected");
        }

        assertThat(workspace.resolve("must-not-exist.txt")).doesNotExist();
        assertThat(calls).hasValue(2);
        assertThat(traces).noneMatch(event -> event.operation().equals("runtime.error"));
        assertThat(new JsonlTranscriptReader(transcripts).read(runId.value()).events())
                .extracting(event -> event.eventType())
                .contains(
                        "policy.decision.made",
                        "approval.requested",
                        "approval.authority.verified",
                        "approval.target.validated",
                        "approval.responded",
                        "run.completed");
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
            if (calls.incrementAndGet() == 1) {
                assertThat(request.tools())
                        .extracting(io.haifa.agent.model.api.ModelToolSpecification::name)
                        .contains("execution_run", "skill_load", "skill_resource_read");
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
                            && "SUCCEEDED".equals(message.toolResultData().get("status"))
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
        assertThat(calls).hasValue(2);
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
            calls.incrementAndGet();
            assertThat(request.tools())
                    .extracting(io.haifa.agent.model.api.ModelToolSpecification::name)
                    .doesNotContain("skill_load", "skill_resource_read");
            assertThat(request.messages())
                    .noneMatch(message -> message.role() == ModelMessageRole.SYSTEM
                            && message.content().contains("Available Skills"));
            return new AgentChatResponse(
                    "cli-no-skills",
                    "stub-model",
                    "complete",
                    List.of(),
                    ModelFinishReason.STOP,
                    ModelUsage.unpriced(5, 2),
                    "stub",
                    Map.of());
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
        assertThat(calls).hasValue(1);
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
