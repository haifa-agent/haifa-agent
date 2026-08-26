package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.application.project.persistence.ProjectPersistenceConfiguration;
import io.haifa.agent.core.error.AgentErrorCode;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelErrorCategory;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelInvocationException;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.runtime.api.InteractionResponse;
import io.haifa.agent.runtime.api.InteractionResponseId;
import io.haifa.agent.runtime.api.InteractionResponseType;
import io.haifa.agent.runtime.core.interaction.InteractionRequest;
import io.haifa.agent.runtime.core.model.continuation.AesGcmModelContinuationProtector;
import io.haifa.agent.skill.api.SkillOrigin;
import io.haifa.agent.skill.api.SkillParserMode;
import io.haifa.agent.store.jsonl.JsonlTranscriptReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("slow")
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
    void windowsExplicitLocalNativeFailsClosedBeforeModelInvocation() {
        org.junit.jupiter.api.Assumptions.assumeTrue(isWindows());
        var model = (io.haifa.agent.model.api.AgentChatModel) request -> {
            throw new AssertionError("unsupported provider must fail before model invocation");
        };
        CliConfiguration defaults = CliConfiguration.defaults();
        CliConfiguration strict = new CliConfiguration(
                defaults.model(),
                defaults.availableModels(),
                defaults.enabledTools(),
                defaults.mcpServers(),
                defaults.web(),
                defaults.skills(),
                localNativeExecution(defaults.execution()),
                defaults.approval(),
                defaults.timeout(),
                defaults.maxIterations(),
                defaults.maxToolCalls(),
                defaults.persistence());

        assertThatThrownBy(() -> LocalCodingAgent.create(
                        workspace,
                        strict,
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
        var isolated = new CliConfiguration(
                defaults.model(),
                defaults.enabledTools(),
                defaults.mcpServers(),
                localNativeExecution(defaults.execution()),
                ApprovalMode.ASK,
                defaults.timeout(),
                defaults.maxIterations(),
                defaults.maxToolCalls());

        assertThat(LocalCodingAgent.effectiveBuiltInTools(denied))
                .doesNotContain("execution.run", "execution.request_permissions");
        assertThat(LocalCodingAgent.effectiveBuiltInTools(defaults))
                .contains("execution.run")
                .doesNotContain("execution.request_permissions");
        assertThat(LocalCodingAgent.effectiveBuiltInTools(isolated))
                .contains("execution.run", "execution.request_permissions");
    }

    @Test
    void tellsTheModelWhichConfiguredShellDialectExecutionRunUses() {
        assertThat(LocalCodingAgent.executionEnvironmentPrompt("PowerShell"))
                .contains(
                        "execution_run uses PowerShell command syntax",
                        "do not assume a POSIX shell",
                        "non-interactive CLI available through the inherited PATH",
                        "rg --files for file discovery",
                        "rg for text search",
                        "dedicated search wrapper",
                        "request_permissions is not a general sandbox bypass",
                        "Keep command output bounded",
                        "execution_output_read",
                        "capturedOutputRef",
                        "nextOffsetBytes")
                .doesNotContain("Host OS:");
        assertThat(LocalCodingAgent.executionEnvironmentPrompt(" ")).isEmpty();
    }

    @Test
    void freezesOneWorkspaceEnvironmentBlockBeforeRootProjectInstructions() throws Exception {
        Files.writeString(workspace.resolve("pom.xml"), "<project/>");
        Files.writeString(workspace.resolve("AGENTS.md"), "Use the root project instruction once.");
        AtomicReference<String> systemPrompt = new AtomicReference<>();
        var model = (io.haifa.agent.model.api.AgentChatModel) request -> {
            systemPrompt.set(request.messages().stream()
                    .filter(message -> message.role() == ModelMessageRole.SYSTEM)
                    .map(message -> message.content())
                    .collect(java.util.stream.Collectors.joining("\n")));
            return answer("workspace-environment", "done");
        };
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

        try (var agent = LocalCodingAgent.create(
                workspace, denied, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), model)) {
            assertThat(awaitTerminal(agent, agent.start("inspect the workspace").runId())
                            .status())
                    .isEqualTo(AgentRunStatus.COMPLETED);
        }

        String prompt = systemPrompt.get();
        assertThat(prompt)
                .contains(
                        "<workspace_environment",
                        "enabled=\"false\"",
                        "network=\"UNAVAILABLE\"",
                        "<project_signals>pom.xml</project_signals>",
                        "root_agents=\"PRESENT\"",
                        "Use the root project instruction once.")
                .doesNotContain(workspace.toString(), "Runtime execution guidance:");
        assertThat(prompt.split("<workspace_environment", -1)).hasSize(2);
        assertThat(prompt.split("Use the root project instruction once\\.", -1)).hasSize(2);
        assertThat(prompt.indexOf("<workspace_environment"))
                .isLessThan(prompt.indexOf("Use the root project instruction once."));
    }

    @Test
    void conversationalPromptsCompleteAfterOneModelResponseEach() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var model = (io.haifa.agent.model.api.AgentChatModel) request -> {
            int call = calls.incrementAndGet();
            return answer("conversation-" + call, call == 1 ? "Hello! How can I help?" : "Paris is in France.");
        };

        try (var agent = LocalCodingAgent.create(
                workspace,
                trustedHostConfiguration(CliConfiguration.defaults()),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                model)) {
            var greeting = awaitTerminal(agent, agent.start("hello").runId());
            assertThat(greeting.status()).isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(greeting.output()).contains("Hello! How can I help?");
            assertThat(calls).hasValue(1);

            var knowledge = awaitTerminal(
                    agent, agent.start("What country is Paris in?").runId());
            assertThat(knowledge.status()).isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(knowledge.output()).contains("Paris is in France.");
            assertThat(calls).hasValue(2);
        }
    }

    @Test
    void providerUnavailableStillFailsWithoutCompletionRepair() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var model = (io.haifa.agent.model.api.AgentChatModel) request -> {
            calls.incrementAndGet();
            throw new ModelInvocationException(
                    ModelErrorCategory.PROVIDER_UNAVAILABLE,
                    true,
                    503,
                    "provider_unavailable",
                    request.callId(),
                    "model provider is unavailable",
                    null);
        };

        try (var agent = LocalCodingAgent.create(
                workspace,
                trustedHostConfiguration(CliConfiguration.defaults()),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                model)) {
            var failed = awaitTerminal(agent, agent.start("hello").runId());
            assertThat(failed.status()).isEqualTo(AgentRunStatus.FAILED);
            assertThat(failed.error().orElseThrow().code()).isEqualTo(AgentErrorCode.MODEL_PROVIDER_UNAVAILABLE);
        }
        assertThat(calls).hasValue(1);
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
            var snapshot = awaitTerminal(agent, runId, Duration.ofSeconds(60));
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
            var snapshot = awaitTerminal(reopened, second.runId(), Duration.ofSeconds(60));
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
                Duration.ofSeconds(60),
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
            var request = awaitPendingInteraction(agent, accepted.runId(), Duration.ofSeconds(60));
            assertThat(agent.runtime().find(accepted.runId()).orElseThrow().status())
                    .isEqualTo(AgentRunStatus.WAITING_APPROVAL);
            awaitCondition(
                    () -> agent.executionSettled(accepted.runId()),
                    Duration.ofSeconds(60),
                    () -> "execution did not settle: "
                            + agent.runtime().find(accepted.runId()).orElseThrow());
            assertThat(agent.executionSettled(accepted.runId())).isTrue();

            agent.runtime()
                    .respond(new InteractionResponse(
                            new InteractionResponseId(agent.identifiers().nextValue()),
                            request.id(),
                            request.runId(),
                            InteractionResponseType.REJECT,
                            List.of(),
                            "test-reject-" + request.id().value(),
                            agent.time().now()));

            var completed = awaitTerminal(agent, accepted.runId(), Duration.ofSeconds(60));
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
    void canonicalizesAbsoluteWorkspaceRootBeforePolicyAndRunsThroughTheCliAssembly() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String command =
                isWindows() ? "Set-Content -NoNewline -Path shell-e2e.txt -Value stub" : "printf stub > shell-e2e.txt";
        var model = (io.haifa.agent.model.api.AgentChatModel) request -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                assertThat(request.tools())
                        .extracting(io.haifa.agent.model.api.ModelToolSpecification::name)
                        .contains("execution_run", "skill_load", "skill_resource_read")
                        .doesNotContain("file_search");
                return toolResponse(
                        "shell-call-1",
                        "execution_run",
                        Map.of(
                                "command",
                                command,
                                "workdir",
                                workspace.toAbsolutePath().normalize().toString(),
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
            var snapshot = awaitTerminal(agent, accepted.runId(), Duration.ofSeconds(10));

            assertThat(snapshot.status())
                    .withFailMessage("run failed: %s", snapshot.error())
                    .isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(snapshot.output()).contains("shell complete");
        }
        assertThat(Files.readString(workspace.resolve("shell-e2e.txt"))).isEqualTo("stub");
        assertThat(calls).hasValue(4);
    }

    @Test
    void fakeModelReadsRetainedExecutionOutputFromTheNextToolTurn() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String command = isWindows()
                ? "1..3000 | ForEach-Object { if ($_ -eq 1500) { 'HIDDEN-FAILURE-MARKER' } else { 'line-' + $_ } }"
                : "i=1; while [ \"$i\" -le 3000 ]; do if [ \"$i\" -eq 1500 ]; then printf '%s\\n' "
                        + "'HIDDEN-FAILURE-MARKER'; else printf 'line-%s\\n' \"$i\"; fi; i=$((i+1)); done";
        var model = (io.haifa.agent.model.api.AgentChatModel) request -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                assertThat(request.tools())
                        .extracting(io.haifa.agent.model.api.ModelToolSpecification::name)
                        .contains("execution_run", "execution_output_read");
                return toolResponse(
                        "large-output",
                        "execution_run",
                        Map.of(
                                "command",
                                command,
                                "workdir",
                                ".",
                                "timeoutMillis",
                                10_000,
                                "description",
                                "Produce a deterministic bounded output fixture",
                                "operationFamily",
                                "TEST"));
            }
            if (call == 2) {
                String executionMessage = request.messages().stream()
                        .filter(message -> message.role() == ModelMessageRole.TOOL)
                        .filter(message -> message.providerCorrelationId()
                                .map(value -> value.value().equals("large-output-call"))
                                .orElse(false))
                        .map(message -> message.content())
                        .findFirst()
                        .orElseThrow();
                assertThat(executionMessage)
                        .contains("capturedOutputRef=execution:", "captureTruncated=false")
                        .doesNotContain("HIDDEN-FAILURE-MARKER");
                var matcher = java.util.regex.Pattern.compile("capturedOutputRef=([^\\s]+)")
                        .matcher(executionMessage);
                assertThat(matcher.find()).isTrue();
                return toolResponse(
                        "output-read",
                        "execution_output_read",
                        Map.of("outputRef", matcher.group(1), "mode", "SEARCH", "query", "HIDDEN-FAILURE-MARKER"));
            }
            assertThat(request.messages())
                    .anyMatch(message -> message.role() == ModelMessageRole.TOOL
                            && message.providerCorrelationId()
                                    .map(value -> value.value().equals("output-read-call"))
                                    .orElse(false)
                            && message.content().contains("HIDDEN-FAILURE-MARKER")
                            && message.content().contains("byteOffset="));
            return answer("output-read-complete", "bounded execution output inspected");
        };

        try (var agent = LocalCodingAgent.create(
                workspace,
                automaticHostConfiguration(),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                model)) {
            var completed = awaitTerminal(
                    agent,
                    agent.start("Inspect the hidden marker in bounded command output.")
                            .runId(),
                    Duration.ofSeconds(30));
            assertThat(completed.status())
                    .withFailMessage("run failed: %s", completed.error())
                    .isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(completed.output()).contains("bounded execution output inspected");
        }
        assertThat(calls).hasValue(3);
    }

    @Test
    void approvedExecutionRunResumesAndReturnsARealToolResult() throws Exception {
        Files.writeString(workspace.resolve(".gitignore"), "target/\n");
        Path generated = Files.createDirectories(workspace.resolve("target")).resolve("generated.jar");
        try (var channel = Files.newByteChannel(generated, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            channel.position(17L * 1024 * 1024);
            channel.write(ByteBuffer.wrap(new byte[] {0}));
        }
        AtomicInteger calls = new AtomicInteger();
        var traces = new CopyOnWriteArrayList<io.haifa.agent.runtime.core.trace.RuntimeTraceEvent>();
        String command = isWindows() ? "Write-Output APPROVED-EXECUTION" : "printf APPROVED-EXECUTION";
        var model = (io.haifa.agent.model.api.AgentChatModel) request -> {
            if (calls.incrementAndGet() == 1) {
                return toolResponse(
                        "approved-execution",
                        "execution_run",
                        Map.of(
                                "command",
                                command,
                                "workdir",
                                ".",
                                "timeoutMillis",
                                5000,
                                "description",
                                "Inspect the configured shell",
                                "operationFamily",
                                "INSPECT"));
            }
            assertThat(request.messages())
                    .anyMatch(message -> message.role() == ModelMessageRole.TOOL
                            && "SUCCEEDED".equals(message.toolResultData().get("status"))
                            && String.valueOf(message.toolResultData().get("output"))
                                    .contains("APPROVED-EXECUTION"));
            return answer("approved-execution-complete", "approved execution completed");
        };

        try (var agent = LocalCodingAgent.create(
                workspace,
                trustedHostConfiguration(CliConfiguration.defaults()),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                model,
                traces::add)) {
            var accepted = agent.start("Inspect the configured shell after approval.");
            var interaction = awaitPendingInteraction(agent, accepted.runId(), Duration.ofSeconds(30));
            agent.runtime()
                    .respond(new InteractionResponse(
                            new InteractionResponseId(agent.identifiers().nextValue()),
                            interaction.id(),
                            interaction.runId(),
                            InteractionResponseType.APPROVE,
                            List.of(),
                            "approve-" + interaction.id().value(),
                            agent.time().now()));

            var completed = awaitTerminal(agent, accepted.runId());
            assertThat(completed.status())
                    .withFailMessage("run failed: %s; traces: %s", completed.error(), traces)
                    .isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(completed.output()).contains("approved execution completed");
        }
        assertThat(calls).hasValue(2);
        assertThat(traces).noneMatch(event -> event.operation().equals("runtime.error"));
    }

    @Test
    void testExecutionIgnoresGradleCacheChangesAndCanComplete() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Files.writeString(workspace.resolve("build.gradle"), "");
        String command;
        if (isWindows()) {
            Files.writeString(
                    workspace.resolve("gradlew.bat"),
                    "@echo off\r\n"
                            + "if not exist .gradle mkdir .gradle\r\n"
                            + "> .gradle\\validation.lock echo cache\r\n"
                            + "exit /b 0\r\n");
            command = ".\\gradlew.bat test";
        } else {
            Path wrapper = workspace.resolve("gradlew");
            Files.writeString(
                    wrapper,
                    "#!/bin/sh\n" + "set -eu\n" + "mkdir -p .gradle\n" + "printf cache > .gradle/validation.lock\n");
            assertThat(wrapper.toFile().setExecutable(true)).isTrue();
            command = "./gradlew test";
        }
        var model = (io.haifa.agent.model.api.AgentChatModel) request -> {
            if (calls.incrementAndGet() == 1) {
                return toolResponse(
                        "gradle-cache-validation",
                        "execution_run",
                        Map.of(
                                "command",
                                command,
                                "workdir",
                                ".",
                                "timeoutMillis",
                                5000,
                                "description",
                                "Run validation that only updates Gradle cache state",
                                "operationFamily",
                                "TEST"));
            }
            assertThat(request.messages())
                    .anyMatch(message -> message.role() == ModelMessageRole.TOOL
                            && "SUCCEEDED".equals(message.toolResultData().get("status"))
                            && message.toolResultData().containsKey("validationEvidence")
                            && message.toolResultData().containsKey("validationAttemptRef")
                            && !message.toolResultData().containsKey("fileChangeSetId"));
            return answer("gradle-cache-validation-complete", "validation completed");
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

        try (var agent = LocalCodingAgent.create(
                workspace,
                automatic,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                model)) {
            var accepted = agent.start("Run the validation and report its result.");
            var completed = awaitTerminal(agent, accepted.runId(), Duration.ofSeconds(20));

            assertThat(completed.status())
                    .withFailMessage("run failed: %s", completed.error())
                    .isEqualTo(AgentRunStatus.COMPLETED);
        }
        assertThat(Files.readString(workspace.resolve(".gradle/validation.lock"))
                        .trim())
                .isEqualTo("cache");
        assertThat(calls).hasValue(2);
    }

    @Test
    void stubModelCompletesChangeAfterDeliveryEvidenceExists() throws Exception {
        Path successfulWorkspace = Files.createDirectory(workspace.resolve("delivery-success"));
        AtomicInteger successfulCalls = new AtomicInteger();
        List<AgentChatRequest> successfulRequests = new CopyOnWriteArrayList<>();
        var successfulModel = (io.haifa.agent.model.api.AgentChatModel) request -> {
            successfulRequests.add(request);
            int call = successfulCalls.incrementAndGet();
            return switch (call) {
                case 1 -> {
                    assertThat(request.messages())
                            .anyMatch(message -> message.role() == ModelMessageRole.SYSTEM
                                    && message.content().contains("smallest complete change")
                                    && message.content().contains("result-verification skill")
                                    && message.content().contains("authoritative tool results show a workspace change"))
                            .noneMatch(message -> message.content().contains("[CODING_RUN_STATE]"));
                    yield toolResponse(
                            "delivery-write", "file_create", Map.of("path", "delivered.txt", "content", "delivered\n"));
                }
                case 2 -> {
                    yield toolResponse(
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
                }
                case 3 ->
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
        assertThat(successfulCalls).hasValue(4);
        for (int index = 1; index < successfulRequests.size(); index++) {
            AgentChatRequest previous = successfulRequests.get(index - 1);
            AgentChatRequest current = successfulRequests.get(index);
            assertThat(current.messages().subList(0, previous.messages().size()))
                    .as("request %s must preserve request %s as its full message prefix", index + 1, index)
                    .containsExactlyElementsOf(previous.messages());
            assertThat(current.tools()).containsExactlyElementsOf(previous.tools());
        }
    }

    @Test
    void stubAnalyzeRunUsesReadOnlyEvidenceWithoutRequiringWorkspaceChange() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "deterministic root cause evidence\n");
        AtomicInteger calls = new AtomicInteger();
        var model = (io.haifa.agent.model.api.AgentChatModel) request -> switch (calls.incrementAndGet()) {
            case 1 -> toolResponse("analyze-read", "file_read", Map.of("path", "README.md"));
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
        assertThat(calls).hasValue(2);
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
            var snapshot = awaitTerminal(agent, accepted.runId(), Duration.ofSeconds(10));
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
            var snapshot = awaitTerminal(agent, accepted.runId(), Duration.ofSeconds(10));
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
            var snapshot = awaitTerminal(agent, accepted.runId(), Duration.ofSeconds(10));
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
            var snapshot = awaitTerminal(agent, accepted.runId(), Duration.ofSeconds(10));

            assertThat(snapshot.status()).isEqualTo(AgentRunStatus.COMPLETED);
        }
        assertThat(calls).hasValue(2);
        assertThat(firstRequest.get().messages().stream()
                        .filter(message -> message.role() == ModelMessageRole.USER)
                        .map(message -> message.content())
                        .toList())
                .satisfiesExactly(
                        content -> assertThat(content).isEqualTo("List the workspace root."),
                        content -> assertThat(content)
                                .startsWith("[CODING_WORK_PROJECTION coding-work-projection/1]")
                                .contains("phase=ORIENT")
                                .doesNotContain(workspace.toString()));
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
            var snapshot = awaitTerminal(agent, accepted.runId(), Duration.ofSeconds(10));

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
        return awaitTerminal(agent, runId, Duration.ofSeconds(30));
    }

    private static io.haifa.agent.runtime.api.AgentRunSnapshot awaitTerminal(
            LocalCodingAgent agent, io.haifa.agent.core.run.AgentRunId runId, Duration timeout)
            throws InterruptedException {
        awaitCondition(
                () -> agent.runtime().find(runId).orElseThrow().status().isTerminal(),
                timeout,
                () -> "run did not become terminal: "
                        + agent.runtime().find(runId).orElseThrow());
        return agent.runtime().find(runId).orElseThrow();
    }

    private static InteractionRequest awaitPendingInteraction(
            LocalCodingAgent agent, io.haifa.agent.core.run.AgentRunId runId, Duration timeout)
            throws InterruptedException {
        awaitCondition(
                () -> agent.interactions().pending(runId).isPresent(),
                timeout,
                () -> "interaction did not become pending: "
                        + agent.runtime().find(runId).orElseThrow());
        return agent.interactions().pending(runId).orElseThrow();
    }

    private static void awaitCondition(
            java.util.function.BooleanSupplier condition,
            Duration timeout,
            java.util.function.Supplier<String> timeoutMessage)
            throws InterruptedException {
        Instant deadline = now().plus(timeout);
        while (!condition.getAsBoolean() && now().isBefore(deadline)) {
            Thread.sleep(25);
        }
        if (!condition.getAsBoolean()) throw new AssertionError(timeoutMessage.get());
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
                        model.providerDisplayName(),
                        model.modelId(),
                        endpoint,
                        endpoint,
                        model.credentialRef(),
                        model.style(),
                        model.dialect(),
                        model.nativeStreaming(),
                        model.workspaceId(),
                        model.region(),
                        model.id(),
                        model.displayName(),
                        model.capabilities(),
                        model.contextWindow(),
                        model.maxOutputTokens()),
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

    private static CliConfiguration.Execution localNativeExecution(CliConfiguration.Execution execution) {
        return new CliConfiguration.Execution(
                "local-native",
                "deny",
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
