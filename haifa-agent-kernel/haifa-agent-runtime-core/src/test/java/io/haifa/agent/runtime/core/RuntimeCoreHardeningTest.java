package io.haifa.agent.runtime.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunOutcome;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.run.AgentRunUsageDelta;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.RuntimeIdempotencyKey;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelToolSpecification;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.InteractionRequestId;
import io.haifa.agent.runtime.api.InteractionResponse;
import io.haifa.agent.runtime.api.InteractionResponseId;
import io.haifa.agent.runtime.api.InteractionResponseType;
import io.haifa.agent.runtime.api.ResumeAgentRunRequest;
import io.haifa.agent.runtime.api.RuntimeCommand;
import io.haifa.agent.runtime.api.RuntimeCommandArguments;
import io.haifa.agent.runtime.api.RuntimeCommandId;
import io.haifa.agent.runtime.api.RuntimeCommandType;
import io.haifa.agent.runtime.api.RuntimeOverrides;
import io.haifa.agent.runtime.core.attempt.AgentRunExecutionAttempt;
import io.haifa.agent.runtime.core.attempt.ExecutionAttemptId;
import io.haifa.agent.runtime.core.bootstrap.RuntimeCallerContext;
import io.haifa.agent.runtime.core.control.RunControlRegistry;
import io.haifa.agent.runtime.core.control.RunControlSignal;
import io.haifa.agent.runtime.core.decision.FinalAnswerDecision;
import io.haifa.agent.runtime.core.decision.ToolCallDecision;
import io.haifa.agent.runtime.core.decision.ToolRequest;
import io.haifa.agent.runtime.core.execution.ManualExecutionScheduler;
import io.haifa.agent.runtime.core.interaction.InMemoryInteractionPort;
import io.haifa.agent.runtime.core.interaction.InteractionRequest;
import io.haifa.agent.runtime.core.middleware.AgentRuntimeMiddleware;
import io.haifa.agent.runtime.core.middleware.RuntimeMiddlewareContext;
import io.haifa.agent.runtime.core.middleware.RuntimeMiddlewareOrder;
import io.haifa.agent.runtime.core.middleware.RuntimePhase;
import io.haifa.agent.runtime.core.retry.RepairRetryPolicy;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import io.haifa.agent.runtime.core.storage.OutboxMessage;
import io.haifa.agent.runtime.core.tool.BoundedToolResultNormalizer;
import io.haifa.agent.runtime.core.trace.RuntimeTraceEvent;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RuntimeCoreHardeningTest {
    private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");

    @Test
    void callerIdentityScopesVisibilityAndStartIdempotency() {
        AtomicReference<RuntimeCallerContext> caller = new AtomicReference<>(caller("tenant-a", "alice"));
        Fixture fixture = fixture(model(finalDecision("done")), builder -> builder.callers(caller::get));
        var alice = fixture.runtime.start(request("shared-key"));

        caller.set(caller("tenant-a", "bob"));
        assertThat(fixture.runtime.find(alice.runId())).isEmpty();
        var bob = fixture.runtime.start(request("shared-key"));

        assertThat(bob.runId()).isNotEqualTo(alice.runId());
        assertThat(AgentRunRequest.class.getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("tenant", "principal", "caller");
    }

    @Test
    void listenerFailureCannotChangeCommittedCompletion() {
        Fixture fixture = fixture(model(finalDecision("committed")));
        fixture.runtime.addListener(snapshot -> {
            throw new IllegalStateException("listener failed");
        });
        var accepted = fixture.runtime.start(request("listener"));
        fixture.scheduler.runAll();
        assertThat(fixture.runtime.find(accepted.runId()).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);
    }

    @Test
    void concurrentDuplicateCommandReturnsOneStableResultAndOneAuditEvent() throws Exception {
        Fixture fixture = fixture(model(finalDecision("unused")));
        var accepted = fixture.runtime.start(request("command-race"));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<Object>> calls = java.util.stream.IntStream.range(0, 12)
                    .mapToObj(index -> (Callable<Object>) () -> fixture.runtime.command(new RuntimeCommand(
                            new RuntimeCommandId("submission-" + index),
                            accepted.runId(),
                            RuntimeCommandType.CANCEL,
                            RuntimeCommandArguments.NONE,
                            "same-command",
                            NOW)))
                    .toList();
            var results = executor.invokeAll(calls).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception error) {
                            throw new RuntimeException(error);
                        }
                    })
                    .toList();
            assertThat(results).containsOnly(results.getFirst());
        }
        assertThat(fixture.store.eventsFor(accepted.runId()).stream()
                        .filter(event -> event.type().startsWith("runtime.command-")))
                .hasSize(1);
    }

    @Test
    void commandIdempotencyIsScopedToTheTargetRun() {
        Fixture fixture = fixture(model(finalDecision("unused")));
        var first = fixture.runtime.start(request("command-run-1"));
        var second = fixture.runtime.start(request("command-run-2"));
        RuntimeCommand firstCommand = new RuntimeCommand(
                new RuntimeCommandId("first-submission"),
                first.runId(),
                RuntimeCommandType.CANCEL,
                RuntimeCommandArguments.NONE,
                "same-key",
                NOW);
        RuntimeCommand secondCommand = new RuntimeCommand(
                new RuntimeCommandId("second-submission"),
                second.runId(),
                RuntimeCommandType.CANCEL,
                RuntimeCommandArguments.NONE,
                "same-key",
                NOW);
        assertThat(fixture.runtime.command(firstCommand).snapshot().runId()).isEqualTo(first.runId());
        assertThat(fixture.runtime.command(secondCommand).snapshot().runId()).isEqualTo(second.runId());
    }

    @Test
    void exhaustedCompletionRepairFailsTheRun() {
        Fixture blocked = fixture(
                model(finalDecision("first"), finalDecision("second")),
                builder -> builder.requiredArtifactChecker((run, decision) -> false)
                        .repairRetry(new RepairRetryPolicy(1)));
        var blockedRun = blocked.runtime.start(request("artifact-blocked"));
        blocked.scheduler.runAll();
        assertThat(blocked.runtime.find(blockedRun.runId()).orElseThrow().status())
                .isEqualTo(AgentRunStatus.FAILED);
    }

    @Test
    void allMiddlewarePhasesRunInDeclaredLoopOrder() {
        List<RuntimePhase> observed = new ArrayList<>();
        RuntimeCoreBuilderCustomizer customizer = builder -> {
            for (RuntimePhase phase : RuntimePhase.values()) {
                builder.middleware(new RecordingMiddleware(phase, observed));
            }
            return builder;
        };
        Fixture fixture = fixture(model(finalDecision("done")), customizer);
        var accepted = fixture.runtime.start(request("middleware"));
        fixture.scheduler.runAll();

        assertThat(fixture.runtime.find(accepted.runId()).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(observed)
                .containsExactly(
                        RuntimePhase.BEFORE_RUN,
                        RuntimePhase.BEFORE_CONTEXT_BUILD,
                        RuntimePhase.AFTER_CONTEXT_BUILD,
                        RuntimePhase.BEFORE_MODEL_CALL,
                        RuntimePhase.AFTER_MODEL_CALL,
                        RuntimePhase.BEFORE_COMPLETION,
                        RuntimePhase.BEFORE_DECISION_EXECUTION,
                        RuntimePhase.AFTER_DECISION_EXECUTION,
                        RuntimePhase.AFTER_COMPLETION);
    }

    @Test
    void controlSignalsHaveDeterministicPriority() {
        RunControlRegistry controls = new RunControlRegistry();
        AgentRunId runId = new AgentRunId("run-1");
        controls.requestPause(runId);
        controls.requestTimeout(runId);
        controls.reportLeaseLost(runId);
        assertThat(controls.signal(runId)).isEqualTo(RunControlSignal.LEASE_LOST);
        controls.requestCancel(runId);
        controls.requestPause(runId);
        assertThat(controls.signal(runId)).isEqualTo(RunControlSignal.CANCEL);
    }

    @Test
    void resumeUsesFrozenConfigurationAndRecordsCheckpointOnNewAttempt() {
        AtomicReference<DefaultAgentRuntime> runtime = new AtomicReference<>();
        AtomicReference<AgentRunId> runId = new AtomicReference<>();
        Queue<AgentChatResponse> responses = new ArrayDeque<>();
        AgentChatModel client = request -> {
            if (responses.isEmpty()) {
                runtime.get()
                        .command(new RuntimeCommand(
                                new RuntimeCommandId("pause"),
                                runId.get(),
                                RuntimeCommandType.PAUSE,
                                RuntimeCommandArguments.NONE,
                                "pause",
                                NOW));
                return response(finalDecision("pause"));
            }
            return responses.remove();
        };
        Fixture fixture = fixture(client);
        runtime.set(fixture.runtime);
        var accepted = fixture.runtime.start(request("resume-checkpoint"));
        runId.set(accepted.runId());
        fixture.scheduler.runAll();
        var before = fixture.store.find(accepted.runId()).orElseThrow().configurationSnapshot();
        responses.add(response(finalDecision("done")));
        fixture.runtime.resume(new ResumeAgentRunRequest("resume", accepted.runId(), List.of()));
        var attempts = fixture.store.attemptsFor(accepted.runId());
        assertThat(attempts.get(1).resumedFromCheckpointId()).isPresent();
        assertThat(fixture.store.find(accepted.runId()).orElseThrow().configurationSnapshot())
                .isEqualTo(before);
    }

    @Test
    void outboxSupportsAtLeastOncePublishingAndConsumerDeduplication() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        OutboxMessage message = new OutboxMessage("event-1", new AgentRunId("run-1"), "created", Map.of(), NOW);
        store.append(message);
        assertThat(store.pending()).containsExactly(message);
        assertThat(store.markConsumed("consumer-a", message.id())).isTrue();
        assertThat(store.markConsumed("consumer-a", message.id())).isFalse();
        assertThat(store.markConsumed("consumer-b", message.id())).isTrue();
        store.markPublished(message.id());
        assertThat(store.pending()).isEmpty();
    }

    @Test
    void toolResultsAreBoundedAndSecretsAreRedacted() {
        var normalizer = new BoundedToolResultNormalizer(4, 3);
        ToolResult normalized = normalizer.normalize(
                TestToolPlatform.binding("read", "1.0.0", "input", false),
                new ToolResult(
                        true,
                        "123456",
                        Map.of(
                                "token", "secret-value",
                                "nested", Map.of("password", "pw"),
                                "overflow", "ignored"),
                        List.of(),
                        List.of(),
                        false));
        assertThat(normalized.summary()).isEqualTo("1234");
        assertThat(normalized.truncated()).isTrue();
        assertThat(normalized.structuredData().get("token")).isEqualTo("[REDACTED]");
        assertThat(((Map<?, ?>) normalized.structuredData().get("nested")).get("password"))
                .isEqualTo("[REDACTED]");
    }

    @Test
    void traceContainsRuntimeCorrelationWithoutUnsafePayloads() {
        List<RuntimeTraceEvent> traces = new ArrayList<>();
        Fixture fixture = fixture(model(finalDecision("done")), builder -> builder.trace(traces::add)
                .workerId("worker-a"));
        var accepted = fixture.runtime.start(request("trace"));
        fixture.scheduler.runAll();
        assertThat(traces).isNotEmpty().allSatisfy(trace -> {
            assertThat(trace.runId()).isEqualTo(accepted.runId());
            assertThat(trace.attemptId()).isPresent();
            assertThat(trace.sessionId()).isEqualTo(new AgentSessionId("session-1"));
            assertThat(trace.workerId()).contains("worker-a");
            assertThat(trace.safeAttributes().keySet())
                    .noneMatch(key -> key.toLowerCase().contains("prompt")
                            || key.toLowerCase().contains("secret"));
        });
    }

    @Test
    void businessToolFailureReturnsToContextAndExecutionCanComplete() {
        ToolRequest request =
                toolRequest("tool-key", "shell", "1.0.0", new ToolArguments("shell.input", "1", Map.of()));
        Fixture fixture = fixture(
                model(new ToolCallDecision(List.of(request)), finalDecision("handled")),
                builder -> TestToolPlatform.install(
                        builder,
                        "shell",
                        "1.0.0",
                        "shell.input",
                        true,
                        ignored ->
                                new ToolResult(false, "exit 2", Map.of("exitCode", 2), List.of(), List.of(), false)));
        var accepted = fixture.runtime.start(request("business-failure"));
        fixture.scheduler.runAll();
        assertThat(fixture.runtime.find(accepted.runId()).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(fixture.store.toolCalls(accepted.runId()).getFirst().status().name())
                .isEqualTo("FAILED");
    }

    @Test
    void businessFailureUsesPlatformFailureEnvelopeInsteadOfSuccessOutputSchema() {
        ToolRequest request =
                toolRequest("remote-failure", "remote", "1.0.0", new ToolArguments("remote.input", "1", Map.of()));
        Map<String, Object> successOnlyOutput = Map.of(
                "$schema",
                io.haifa.agent.tool.api.ToolSchema.DRAFT_2020_12,
                "type",
                "object",
                "required",
                List.of("successValue"),
                "properties",
                Map.of("successValue", Map.of("type", "string")),
                "additionalProperties",
                false);
        Fixture fixture = fixture(
                model(new ToolCallDecision(List.of(request)), finalDecision("handled")),
                builder -> TestToolPlatform.installWithOutputSchema(
                        builder,
                        "remote",
                        "1.0.0",
                        "remote.input",
                        successOnlyOutput,
                        ignored -> new ToolResult(
                                false,
                                "remote business error",
                                Map.of("error", "invalid request"),
                                List.of(),
                                List.of(),
                                false)));

        var accepted = fixture.runtime.start(request("remote-business-failure"));
        fixture.scheduler.runAll();

        assertThat(fixture.runtime.find(accepted.runId()).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(fixture.store.toolCalls(accepted.runId()).getFirst().status().name())
                .isEqualTo("FAILED");
    }

    @Test
    void budgetConvergenceInstructionReachesTheModelContext() {
        AtomicReference<List<String>> messages = new AtomicReference<>();
        Fixture nearBudget = fixture(request -> {
            messages.set(request.messages().stream()
                    .map(message -> message.content())
                    .toList());
            return response(finalDecision("done"));
        });
        var accepted = nearBudget.runtime.start(request("soft-budget"));
        var run = nearBudget.store.find(accepted.runId()).orElseThrow();
        long expected = run.version();
        run.recordUsage(new AgentRunUsageDelta(0, 0, 0, 52, 0, 0, 0, 0));
        nearBudget.store.save(run, expected);
        nearBudget.scheduler.runAll();
        assertThat(messages.get()).anyMatch(value -> value.contains("resource budget"));
    }

    @Test
    void recoveryNeverBlindlyReplaysAnUncertainSideEffectingTool() {
        AtomicInteger calls = new AtomicInteger();
        ToolRequest tool =
                toolRequest("write-once", "write", "1.0.0", new ToolArguments("write.input", "1", Map.of("value", 1)));
        Fixture fixture = fixture(
                model(new ToolCallDecision(List.of(tool))),
                builder -> TestToolPlatform.install(builder, "write", "1.0.0", "write.input", true, request -> {
                    calls.incrementAndGet();
                    throw new AssertionError("process died after external side effect");
                }));
        var accepted = fixture.runtime.start(request("uncertain-write"));
        assertThatThrownBy(fixture.scheduler::runNext).isInstanceOf(AssertionError.class);
        assertThat(fixture.store.toolCalls(accepted.runId()).getFirst().status().name())
                .isEqualTo("RUNNING");

        fixture.runtime.recover(accepted.runId());
        fixture.scheduler.runAll();
        assertThat(calls).hasValue(1);
        assertThat(fixture.runtime.find(accepted.runId()).orElseThrow().status())
                .isEqualTo(AgentRunStatus.FAILED);
        assertThat(fixture.store.attemptsFor(accepted.runId()).getLast().error())
                .isPresent();
    }

    @Test
    void semanticDuplicateToolCallsAreDetected() {
        ToolRequest first =
                toolRequest("key-1", "read", "1.0.0", new ToolArguments("read.input", "1", Map.of("path", "same")));
        ToolRequest second =
                toolRequest("key-2", "read", "1.0.0", new ToolArguments("read.input", "1", Map.of("path", "same")));
        ToolRequest third =
                toolRequest("key-3", "read", "1.0.0", new ToolArguments("read.input", "1", Map.of("path", "same")));
        AtomicInteger executions = new AtomicInteger();
        Fixture duplicate = fixture(
                model(
                        new ToolCallDecision(List.of(first)),
                        new ToolCallDecision(List.of(second)),
                        new ToolCallDecision(List.of(third))),
                builder -> TestToolPlatform.install(builder, "read", "1.0.0", "read.input", false, request -> {
                    executions.incrementAndGet();
                    return new ToolResult(true, "ok", Map.of(), List.of(), List.of(), false);
                }));
        var duplicateRun = duplicate.runtime.start(request("semantic-duplicate"));
        duplicate.scheduler.runAll();
        assertThat(executions).hasValue(2);
        assertThat(duplicate.runtime.find(duplicateRun.runId()).orElseThrow().status())
                .isEqualTo(AgentRunStatus.FAILED);
    }

    @Test
    void lostExecutionOwnershipStopsBeforeAnyModelCall() {
        AtomicInteger calls = new AtomicInteger();
        Fixture fixture = fixture(
                request -> {
                    calls.incrementAndGet();
                    return response(finalDecision("should not run"));
                },
                builder -> builder.executionOwnership(attempt -> false));
        var accepted = fixture.runtime.start(request("lost-lease"));
        fixture.scheduler.runAll();
        assertThat(calls).hasValue(0);
        assertThat(fixture.runtime.find(accepted.runId()).orElseThrow().status())
                .isEqualTo(AgentRunStatus.FAILED);
    }

    @Test
    void cancelWinsACompletionRaceAtThePostModelSafePoint() {
        AtomicReference<DefaultAgentRuntime> runtime = new AtomicReference<>();
        AtomicReference<AgentRunId> runId = new AtomicReference<>();
        Fixture fixture = fixture(request -> {
            runtime.get().command(command(runId.get(), "cancel-during-model"));
            return response(finalDecision("must not commit"));
        });
        runtime.set(fixture.runtime);
        var accepted = fixture.runtime.start(request("cancel-race"));
        runId.set(accepted.runId());
        fixture.scheduler.runAll();
        assertThat(fixture.runtime.find(accepted.runId()).orElseThrow().status())
                .isEqualTo(AgentRunStatus.CANCELLED);
        assertThat(fixture.store.output(accepted.runId())).isEmpty();
    }

    @Test
    void storeAllowsOnlyOneActiveExecutorPerRun() {
        Fixture fixture = fixture(model(finalDecision("unused")));
        var accepted = fixture.runtime.start(request("single-executor"));
        assertThatThrownBy(() -> fixture.store.insert(new AgentRunExecutionAttempt(
                        new ExecutionAttemptId("competing-attempt"), accepted.runId(), 2, NOW, Optional.empty())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active attempt");
    }

    @Test
    void interactionRejectsExpiredWrongOperatorAndConflictingResponses() {
        InMemoryInteractionPort interactions = new InMemoryInteractionPort();
        AgentRunId runId = new AgentRunId("interaction-run");
        InteractionRequestId requestId = new InteractionRequestId("request-1");
        RuntimeCallerContext owner = caller("tenant", "owner");
        interactions.create(new InteractionRequest(
                requestId,
                runId,
                owner.tenant(),
                owner.principal(),
                "approval",
                "approve?",
                true,
                NOW,
                NOW.plusSeconds(60)));
        InteractionResponse response = new InteractionResponse(
                new InteractionResponseId("response-1"),
                requestId,
                runId,
                InteractionResponseType.APPROVE,
                List.of(),
                "key",
                NOW);
        assertThatThrownBy(() -> interactions.respond(response, caller("tenant", "intruder"), NOW))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> interactions.respond(response, owner, NOW.plusSeconds(61)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expired");
        assertThat(interactions.respond(response, owner, NOW).newlyRecorded()).isTrue();
        InteractionResponse conflict = new InteractionResponse(
                response.responseId(), requestId, runId, InteractionResponseType.REJECT, List.of(), "other", NOW);
        assertThatThrownBy(() -> interactions.respond(conflict, owner, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("response id");
    }

    private static Fixture fixture(AgentChatModel model) {
        return fixture(model, builder -> builder);
    }

    private static Fixture fixture(AgentChatModel model, RuntimeCoreBuilderCustomizer customizer) {
        ManualExecutionScheduler scheduler = new ManualExecutionScheduler();
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AtomicInteger sequence = new AtomicInteger();
        IdentifierGenerator ids = () -> "hardening-id-" + sequence.incrementAndGet();
        TimeProvider time = () -> NOW;
        RuntimeCoreBuilder builder = new RuntimeCoreBuilder()
                .registerChatModel("openai-compatible", "1.0.0", model)
                .scheduler(scheduler)
                .store(store)
                .identifierGenerator(ids)
                .timeProvider(time);
        return new Fixture(customizer.apply(builder).build(), scheduler, store);
    }

    private static AgentChatModel model(io.haifa.agent.runtime.core.decision.AgentDecision... decisions) {
        Queue<io.haifa.agent.runtime.core.decision.AgentDecision> queue = new ArrayDeque<>(List.of(decisions));
        return request -> response(queue.remove());
    }

    private static AgentChatResponse response(io.haifa.agent.runtime.core.decision.AgentDecision decision) {
        if (decision instanceof FinalAnswerDecision answer) {
            return new AgentChatResponse(
                    "response",
                    "deepseek-v4-pro",
                    answer.summary(),
                    List.of(),
                    ModelFinishReason.STOP,
                    ModelUsage.unpriced(1, 1),
                    "",
                    Map.of());
        }
        if (decision instanceof ToolCallDecision tools) {
            List<ModelToolCall> calls = tools.requests().stream()
                    .map(tool -> new ModelToolCall(
                            tool.providerCorrelationId(),
                            tool.toolName(),
                            tool.arguments().values()))
                    .toList();
            return new AgentChatResponse(
                    "response",
                    "deepseek-v4-pro",
                    "",
                    calls,
                    ModelFinishReason.TOOL_CALLS,
                    ModelUsage.unpriced(1, 1),
                    "",
                    Map.of());
        }
        throw new IllegalArgumentException("decision is not representable by the Model API response contract");
    }

    private static ToolRequest toolRequest(String key, String name, String version, ToolArguments arguments) {
        return new ToolRequest(
                new ToolCallId("domain-" + key),
                new ProviderToolCallCorrelationId("provider-" + key),
                new RuntimeIdempotencyKey("runtime-" + key),
                name,
                version,
                arguments);
    }

    private static ModelToolSpecification toolSpecification(String name, String version, String schemaId) {
        return new ModelToolSpecification(
                name, version, "Test tool " + name, schemaId, version, Map.of("type", "object"), false);
    }

    private static FinalAnswerDecision finalDecision(String summary) {
        return new FinalAnswerDecision(
                AgentRunOutcome.SUCCESS, summary, "test.result", "1.0", Map.of(), List.of(), List.of());
    }

    private static AgentRunRequest request(String key) {
        return new AgentRunRequest(
                key,
                new AgentDefinitionId("test-agent"),
                Optional.empty(),
                "test-profile",
                new AgentSessionId("session-1"),
                Optional.empty(),
                "objective",
                List.of(),
                RuntimeOverrides.NONE);
    }

    private static RuntimeCommand command(AgentRunId runId, String key) {
        return new RuntimeCommand(
                new RuntimeCommandId(key), runId, RuntimeCommandType.CANCEL, RuntimeCommandArguments.NONE, key, NOW);
    }

    private static RuntimeCallerContext caller(String tenant, String principal) {
        return new RuntimeCallerContext(new TenantRef(tenant), new PrincipalRef(principal, "user"));
    }

    @FunctionalInterface
    private interface RuntimeCoreBuilderCustomizer {
        RuntimeCoreBuilder apply(RuntimeCoreBuilder builder);
    }

    private record Fixture(
            DefaultAgentRuntime runtime, ManualExecutionScheduler scheduler, InMemoryRuntimeStore store) {}

    private record RecordingMiddleware(RuntimePhase phase, List<RuntimePhase> observed)
            implements AgentRuntimeMiddleware {
        @Override
        public RuntimeMiddlewareOrder order() {
            return new RuntimeMiddlewareOrder(10_000);
        }

        @Override
        public void apply(RuntimeMiddlewareContext context) {
            observed.add(phase);
        }
    }
}
