package io.haifa.agent.runtime.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.plan.AgentPlan;
import io.haifa.agent.core.plan.AgentPlanId;
import io.haifa.agent.core.plan.TodoItem;
import io.haifa.agent.core.plan.TodoItemId;
import io.haifa.agent.core.plan.TodoPriority;
import io.haifa.agent.core.run.AgentRunOutcome;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.RuntimeIdempotencyKey;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelToolSpecification;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.InteractionResponse;
import io.haifa.agent.runtime.api.InteractionResponseId;
import io.haifa.agent.runtime.api.InteractionResponseType;
import io.haifa.agent.runtime.api.ResumeAgentRunRequest;
import io.haifa.agent.runtime.api.RuntimeCommand;
import io.haifa.agent.runtime.api.RuntimeCommandArguments;
import io.haifa.agent.runtime.api.RuntimeCommandId;
import io.haifa.agent.runtime.api.RuntimeCommandStatus;
import io.haifa.agent.runtime.api.RuntimeCommandType;
import io.haifa.agent.runtime.api.RuntimeOverrides;
import io.haifa.agent.runtime.core.decision.FinalAnswerDecision;
import io.haifa.agent.runtime.core.decision.ToolCallDecision;
import io.haifa.agent.runtime.core.decision.ToolRequest;
import io.haifa.agent.runtime.core.execution.ManualExecutionScheduler;
import io.haifa.agent.runtime.core.interaction.InMemoryInteractionPort;
import io.haifa.agent.runtime.core.interaction.ToolApprovalTarget;
import io.haifa.agent.runtime.core.retry.BackoffStrategy;
import io.haifa.agent.runtime.core.retry.RetryPolicy;
import io.haifa.agent.runtime.core.retry.RuntimeBackoffPolicy;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import io.haifa.agent.runtime.core.storage.OptimisticLockException;
import io.haifa.agent.runtime.core.tool.ToolPolicyDecision;
import java.time.Duration;
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

class RuntimeCoreTest {
    @Test
    void startsAsQueuedFreezesConfigurationAndCompletesAsynchronously() throws Exception {
        Fixture fixture = fixture(model(finalDecision("done")));
        var accepted = fixture.runtime.start(request("start-1"));

        assertThat(accepted.status()).isEqualTo(AgentRunStatus.QUEUED);
        assertThat(fixture.scheduler.pending()).isEqualTo(1);
        assertThat(fixture.runtime.handle(accepted.runId()).awaitCompletion(Duration.ZERO))
                .isEmpty();
        fixture.scheduler.runAll();

        var completed = fixture.runtime.find(accepted.runId()).orElseThrow();
        assertThat(completed.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(completed.output()).contains("done");
        assertThat(fixture.store.attemptsFor(accepted.runId())).hasSize(1);
        assertThat(fixture.store.checkpointsFor(accepted.runId())).isNotEmpty();
        assertThat(fixture.store.eventsFor(accepted.runId()).stream().map(event -> event.sequence()))
                .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(
                                1, fixture.store.eventsFor(accepted.runId()).size())
                        .boxed()
                        .toList());
    }

    @Test
    void executesToolsSequentiallyThroughTheDurablePipeline() {
        ToolRequest first =
                toolRequest("tool-1", "echo", "1.0.0", new ToolArguments("echo.input", "1.0", Map.of("v", 1)));
        ToolRequest second =
                toolRequest("tool-2", "echo", "1.0.0", new ToolArguments("echo.input", "1.0", Map.of("v", 2)));
        List<Integer> order = new ArrayList<>();
        Fixture fixture = fixture(
                model(new ToolCallDecision(List.of(first, second)), finalDecision("tools done")),
                builder -> TestToolPlatform.install(builder, "echo", "1.0.0", "echo.input", false, request -> {
                    order.add((Integer) request.arguments().values().get("v"));
                    return new ToolResult(true, "echoed", request.arguments().values(), List.of(), List.of(), false);
                }));

        var accepted = fixture.runtime.start(request("tools"));
        fixture.scheduler.runAll();

        assertThat(order).containsExactly(1, 2);
        assertThat(fixture.store.toolCalls(accepted.runId()))
                .hasSize(2)
                .allMatch(call -> call.status().name().equals("COMPLETED"));
        assertThat(fixture.store.find(accepted.runId()).orElseThrow().usage().toolCalls())
                .isEqualTo(2);
    }

    @Test
    void providerToolArgumentsUseTheDisclosedRuntimeSchema() {
        ToolRequest invalid =
                toolRequest("bad-tool", "echo", "1.0.0", new ToolArguments("wrong.schema", "1.0", Map.of()));
        Fixture fixture = fixture(
                model(new ToolCallDecision(List.of(invalid)), finalDecision("repaired")),
                builder -> TestToolPlatform.install(
                        builder,
                        "echo",
                        "1.0.0",
                        "echo.input",
                        false,
                        request ->
                                new ToolResult(true, "used disclosed schema", Map.of(), List.of(), List.of(), false)));
        var accepted = fixture.runtime.start(request("repair"));
        fixture.scheduler.runAll();

        assertThat(fixture.runtime.find(accepted.runId()).orElseThrow().status())
                .as(
                        "attempts=%s events=%s steps=%s calls=%s",
                        fixture.store.attemptsFor(accepted.runId()).stream()
                                .map(attempt -> attempt.error())
                                .toList(),
                        fixture.store.eventsFor(accepted.runId()),
                        fixture.store.steps(accepted.runId()),
                        fixture.store.toolCalls(accepted.runId()))
                .isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(fixture.store.toolCalls(accepted.runId()))
                .allMatch(call -> call.status().name().equals("COMPLETED"))
                .allMatch(call -> call.arguments().schemaId().equals("echo.input"));
    }

    @Test
    void pausesOnlyAtASafePointAndResumeCreatesANewAttempt() {
        AtomicReference<DefaultAgentRuntime> runtime = new AtomicReference<>();
        AtomicReference<String> runId = new AtomicReference<>();
        Queue<AgentChatResponse> decisions = new ArrayDeque<>();
        AgentChatModel model = request -> {
            if (decisions.isEmpty()) {
                runtime.get().command(command(runId.get(), RuntimeCommandType.PAUSE, "pause-1"));
                return response(finalDecision("checkpoint me"));
            }
            return decisions.remove();
        };
        Fixture fixture = fixture(model);
        runtime.set(fixture.runtime);
        var accepted = fixture.runtime.start(request("pause"));
        runId.set(accepted.runId().value());
        fixture.scheduler.runAll();
        assertThat(fixture.runtime.find(accepted.runId()).orElseThrow().status())
                .isEqualTo(AgentRunStatus.SUSPENDED);
        assertThat(fixture.store.checkpointsFor(accepted.runId())).isNotEmpty();
        assertThat(fixture.store.eventsFor(accepted.runId()))
                .anyMatch(event -> event.type().equals("run.safe-point"));

        decisions.add(response(finalDecision("resumed")));
        var resumeRequest = new ResumeAgentRunRequest("resume-1", accepted.runId(), List.of());
        var resumed = fixture.runtime.resume(resumeRequest);
        assertThat(resumed.status()).isEqualTo(AgentRunStatus.RUNNING);
        assertThat(fixture.runtime.resume(resumeRequest).runId()).isEqualTo(accepted.runId());
        assertThat(fixture.store.attemptsFor(accepted.runId())).hasSize(2);
        fixture.scheduler.runAll();
        assertThat(fixture.store.attemptsFor(accepted.runId())).hasSize(2);
        assertThat(fixture.runtime.find(accepted.runId()).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);
    }

    @Test
    void rejectsUnknownAliasWithoutInvokingProvider() {
        AtomicInteger providerCalls = new AtomicInteger();
        Fixture fixture = fixture(
                model(new ToolCallDecision(List.of(
                        toolRequest("unknown", "missing", "1.0.0", new ToolArguments("echo.input", "1.0", Map.of()))))),
                builder -> TestToolPlatform.install(builder, "echo", "1.0.0", "echo.input", false, request -> {
                    providerCalls.incrementAndGet();
                    return new ToolResult(true, "unexpected", Map.of(), List.of(), List.of(), false);
                }));

        var accepted = fixture.runtime.start(request("invalid-tool-identity"));
        fixture.scheduler.runAll();

        assertThat(fixture.runtime.find(accepted.runId()).orElseThrow().status())
                .isEqualTo(AgentRunStatus.FAILED);
        assertThat(providerCalls).hasValue(0);
        assertThat(fixture.store.toolCalls(accepted.runId())).isEmpty();
    }

    @Test
    void cancelAndCommandsAreIdempotent() {
        Fixture fixture = fixture(model(finalDecision("unused")));
        var accepted = fixture.runtime.start(request("cancel"));
        RuntimeCommand command = command(accepted.runId().value(), RuntimeCommandType.CANCEL, "cancel-1");
        assertThat(fixture.runtime.command(command).status()).isEqualTo(RuntimeCommandStatus.ACCEPTED);
        assertThat(fixture.runtime.command(command).status()).isEqualTo(RuntimeCommandStatus.ACCEPTED);
        assertThat(fixture.runtime.find(accepted.runId()).orElseThrow().status())
                .isEqualTo(AgentRunStatus.CANCELLED);
    }

    @Test
    void concurrentStartUsesOneLogicalRunAndOneAttempt() throws Exception {
        Fixture fixture = fixture(model(finalDecision("done")));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<String>> calls = java.util.stream.IntStream.range(0, 20)
                    .mapToObj(index -> (Callable<String>) () ->
                            fixture.runtime.start(request("same-key")).runId().value())
                    .toList();
            var ids = executor.invokeAll(calls).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception error) {
                            throw new RuntimeException(error);
                        }
                    })
                    .toList();
            assertThat(ids).containsOnly(ids.getFirst());
        }
        assertThat(fixture.store.attemptsFor(new io.haifa.agent.core.run.AgentRunId(
                        fixture.runtime.start(request("same-key")).runId().value())))
                .hasSize(1);
    }

    @Test
    void storeEnforcesOptimisticLocking() {
        Fixture fixture = fixture(model(finalDecision("done")));
        var accepted = fixture.runtime.start(request("optimistic-lock"));
        fixture.scheduler.runAll();
        assertThat(fixture.runtime.find(accepted.runId()).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);

        var run = fixture.store.find(accepted.runId()).orElseThrow();
        assertThatThrownBy(() -> fixture.store.save(run, run.version() - 1))
                .isInstanceOf(OptimisticLockException.class);
    }

    @Test
    void retriesModelsAndReadOnlyToolsButNeverAutomaticallyReplaysSideEffects() {
        AtomicInteger modelCalls = new AtomicInteger();
        AgentChatModel transientModel = request -> {
            if (modelCalls.incrementAndGet() == 1) throw new IllegalStateException("transient model failure");
            return response(finalDecision("retried"));
        };
        Fixture modelFixture = fixture(
                transientModel,
                builder -> builder.modelRetry(new RetryPolicy(2, error -> true, BackoffStrategy.none())));
        var modelRun = modelFixture.runtime.start(request("model-retry"));
        modelFixture.scheduler.runAll();
        assertThat(modelCalls).hasValue(2);
        assertThat(modelFixture.runtime.find(modelRun.runId()).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);

        ToolRequest readRequest =
                toolRequest("read-1", "read", "1.0.0", new ToolArguments("read.input", "1.0", Map.of()));
        AtomicInteger readCalls = new AtomicInteger();
        Fixture readFixture = fixture(
                model(new ToolCallDecision(List.of(readRequest)), finalDecision("read complete")),
                builder -> TestToolPlatform.install(
                        builder.toolRetry(new RetryPolicy(2, error -> true, BackoffStrategy.none())),
                        "read",
                        "1.0.0",
                        "read.input",
                        false,
                        request -> {
                            if (readCalls.incrementAndGet() == 1) throw new IllegalStateException("retry read");
                            return new ToolResult(true, "read", Map.of(), List.of(), List.of(), false);
                        }));
        var readRun = readFixture.runtime.start(request("read-retry"));
        readFixture.scheduler.runAll();
        assertThat(readCalls).hasValue(2);
        assertThat(readFixture.runtime.find(readRun.runId()).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);

        ToolRequest writeRequest =
                toolRequest("write-1", "write", "1.0.0", new ToolArguments("write.input", "1.0", Map.of()));
        AtomicInteger writeCalls = new AtomicInteger();
        Fixture writeFixture = fixture(
                model(new ToolCallDecision(List.of(writeRequest))),
                builder -> TestToolPlatform.install(
                        builder.toolRetry(new RetryPolicy(3, error -> true, BackoffStrategy.none())),
                        "write",
                        "1.0.0",
                        "write.input",
                        true,
                        request -> {
                            writeCalls.incrementAndGet();
                            throw new IllegalStateException("uncertain write");
                        }));
        var writeRun = writeFixture.runtime.start(request("write-no-retry"));
        writeFixture.scheduler.runAll();
        assertThat(writeCalls).hasValue(1);
        assertThat(writeFixture.runtime.find(writeRun.runId()).orElseThrow().status())
                .isEqualTo(AgentRunStatus.FAILED);

        var backoff = new RuntimeBackoffPolicy(Duration.ofMillis(10), Duration.ofMillis(25), 2);
        assertThat(backoff.delay(1)).isEqualTo(Duration.ofMillis(10));
        assertThat(backoff.delay(4)).isEqualTo(Duration.ofMillis(25));
    }

    @Test
    void rejectsDuplicateToolCalls() {
        ToolRequest duplicate =
                toolRequest("same-key", "echo", "1.0.0", new ToolArguments("echo.input", "1.0", Map.of()));
        Fixture duplicateFixture = fixture(
                model(new ToolCallDecision(List.of(duplicate, duplicate))),
                builder -> TestToolPlatform.install(
                        builder,
                        "echo",
                        "1.0.0",
                        "echo.input",
                        false,
                        request -> new ToolResult(true, "unused", Map.of(), List.of(), List.of(), false)));
        var duplicateRun = duplicateFixture.runtime.start(request("duplicate-tool"));
        duplicateFixture.scheduler.runAll();
        assertThat(duplicateFixture
                        .runtime
                        .find(duplicateRun.runId())
                        .orElseThrow()
                        .status())
                .isEqualTo(AgentRunStatus.FAILED);
        assertThat(duplicateFixture.store.toolCalls(duplicateRun.runId()))
                .hasSize(2)
                .allSatisfy(call -> {
                    assertThat(call.id().value())
                            .isNotEqualTo(call.providerCorrelationId().value());
                    assertThat(call.id().value())
                            .isNotEqualTo(call.idempotencyKey().value());
                    assertThat(call.providerCorrelationId().value())
                            .isNotEqualTo(call.idempotencyKey().value());
                });
    }

    @Test
    void recoversFromAnAbandonedAttemptAtTheLatestCheckpoint() {
        AtomicInteger calls = new AtomicInteger();
        ToolRequest progress =
                toolRequest("progress", "read", "1.0.0", new ToolArguments("read.input", "1.0", Map.of()));
        AgentChatModel model = request -> {
            int call = calls.incrementAndGet();
            if (call == 1) return response(new ToolCallDecision(List.of(progress)));
            if (call == 2) throw new AssertionError("simulated process loss");
            return response(finalDecision("recovered"));
        };
        Fixture fixture = fixture(
                model,
                builder -> TestToolPlatform.install(
                        builder,
                        "read",
                        "1.0.0",
                        "read.input",
                        false,
                        request -> new ToolResult(true, "progress", Map.of(), List.of(), List.of(), false)));
        var accepted = fixture.runtime.start(request("recover"));

        assertThatThrownBy(fixture.scheduler::runNext).isInstanceOf(AssertionError.class);
        assertThat(fixture.store.find(accepted.runId()).orElseThrow().status()).isEqualTo(AgentRunStatus.RUNNING);
        assertThat(fixture.store.checkpointsFor(accepted.runId())).isNotEmpty();

        fixture.runtime.recover(accepted.runId());
        fixture.scheduler.runAll();
        assertThat(fixture.runtime.find(accepted.runId()).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(fixture.store.attemptsFor(accepted.runId())).hasSize(2);
        assertThat(fixture.store
                        .attemptsFor(accepted.runId())
                        .getFirst()
                        .status()
                        .name())
                .isEqualTo("ABANDONED");
    }

    @Test
    void completionWaitsForTodoConvergenceAndKeepsPartialSuccessStructured() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<InMemoryRuntimeStore> state = new AtomicReference<>();
        AtomicReference<io.haifa.agent.core.run.AgentRunId> runId = new AtomicReference<>();
        AgentChatModel model = request -> {
            if (calls.incrementAndGet() == 2) {
                TodoItem todo =
                        state.get().plan(runId.get()).orElseThrow().items().getFirst();
                todo.start(java.util.Set.of(), Instant.parse("2026-07-21T00:00:00Z"));
                todo.complete("verified", Instant.parse("2026-07-21T00:00:00Z"));
            }
            return response(finalDecision("complete"));
        };
        Fixture fixture = fixture(model);
        state.set(fixture.store);
        var accepted = fixture.runtime.start(request("todo"));
        runId.set(accepted.runId());
        fixture.store.savePlan(new AgentPlan(
                new AgentPlanId("plan-1"),
                accepted.runId(),
                "finish",
                List.of(new TodoItem(
                        new TodoItemId("todo-1"), "verify", "verify output", TodoPriority.HIGH, List.of())),
                Instant.parse("2026-07-21T00:00:00Z")));
        fixture.scheduler.runAll();

        var run = fixture.store.find(accepted.runId()).orElseThrow();
        assertThat(calls).hasValue(2);
        assertThat(run.result().orElseThrow().outcome()).isEqualTo(AgentRunOutcome.SUCCESS);
    }

    @Test
    void asynchronousToolApprovalPausesWorkerAndResumesSameCallInANewAttempt() {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        AgentChatModel model = ignored -> response(
                modelCalls.incrementAndGet() == 1
                        ? new ToolCallDecision(List.of(toolRequest(
                                "approved", "write", "1.0.0", new ToolArguments("write.input", "1.0", Map.of("v", 1)))))
                        : finalDecision("approved done"));
        Fixture fixture = fixture(
                model,
                builder -> TestToolPlatform.install(
                        builder,
                        "write",
                        "1.0.0",
                        "write.input",
                        true,
                        ToolPolicyDecision.REQUIRE_APPROVAL,
                        request -> {
                            toolCalls.incrementAndGet();
                            return new ToolResult(true, "written", Map.of(), List.of(), List.of(), false);
                        }));

        var accepted = fixture.runtime.start(request("async-approve"));
        fixture.scheduler.runAll();

        assertThat(fixture.runtime.find(accepted.runId()).orElseThrow().status())
                .isEqualTo(AgentRunStatus.WAITING_APPROVAL);
        assertThat(fixture.store
                        .attemptsFor(accepted.runId())
                        .getFirst()
                        .status()
                        .name())
                .isEqualTo("PAUSED");
        assertThat(fixture.store.checkpointsFor(accepted.runId())).isNotEmpty();
        assertThat(toolCalls).hasValue(0);
        var interaction = fixture.interactions.pending(accepted.runId()).orElseThrow();
        ToolCallId originalToolCallId =
                fixture.store.toolCalls(accepted.runId()).getFirst().id();
        assertThat(interaction.target()).isInstanceOf(ToolApprovalTarget.class);
        var response = new InteractionResponse(
                new InteractionResponseId("approval-response"),
                interaction.id(),
                accepted.runId(),
                InteractionResponseType.APPROVE,
                List.of(),
                "approval-key",
                Instant.parse("2026-07-21T00:00:00Z"));
        fixture.runtime.respond(response);
        fixture.scheduler.runAll();

        assertThat(fixture.runtime.find(accepted.runId()).orElseThrow().status())
                .as(
                        "attempts=%s events=%s steps=%s calls=%s",
                        fixture.store.attemptsFor(accepted.runId()).stream()
                                .map(attempt -> attempt.error())
                                .toList(),
                        fixture.store.eventsFor(accepted.runId()),
                        fixture.store.steps(accepted.runId()),
                        fixture.store.toolCalls(accepted.runId()))
                .isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(toolCalls).hasValue(1);
        assertThat(modelCalls).hasValue(2);
        assertThat(fixture.store.toolCalls(accepted.runId())).singleElement().satisfies(call -> assertThat(call.id())
                .isEqualTo(originalToolCallId));
        assertThat(fixture.store.attemptsFor(accepted.runId())).hasSize(2);
    }

    @Test
    void rejectedToolApprovalDoesNotCancelRunAndDuplicateResponseIsIdempotent() {
        AtomicInteger toolCalls = new AtomicInteger();
        AtomicReference<AgentChatRequest> resumedModelRequest = new AtomicReference<>();
        Queue<io.haifa.agent.runtime.core.decision.AgentDecision> decisions = new ArrayDeque<>(List.of(
                new ToolCallDecision(List.of(
                        toolRequest("rejected", "write", "1.0.0", new ToolArguments("write.input", "1.0", Map.of())))),
                finalDecision("continued after rejection")));
        AgentChatModel approvalModel = request -> {
            if (request.iteration() == 2) resumedModelRequest.set(request);
            return response(decisions.remove());
        };
        Fixture fixture = fixture(
                approvalModel,
                builder -> TestToolPlatform.install(
                        builder,
                        "write",
                        "1.0.0",
                        "write.input",
                        true,
                        ToolPolicyDecision.REQUIRE_APPROVAL,
                        request -> {
                            toolCalls.incrementAndGet();
                            return new ToolResult(true, "unexpected", Map.of(), List.of(), List.of(), false);
                        }));
        var accepted = fixture.runtime.start(request("async-reject"));
        fixture.scheduler.runAll();
        var interaction = fixture.interactions.pending(accepted.runId()).orElseThrow();
        var response = new InteractionResponse(
                new InteractionResponseId("rejection-response"),
                interaction.id(),
                accepted.runId(),
                InteractionResponseType.REJECT,
                List.of(),
                "rejection-key",
                Instant.parse("2026-07-21T00:00:00Z"));

        fixture.runtime.respond(response);
        fixture.scheduler.runAll();
        var duplicate = fixture.runtime.respond(response);

        assertThat(duplicate.status())
                .as(
                        "attempts=%s events=%s steps=%s calls=%s",
                        fixture.store.attemptsFor(accepted.runId()).stream()
                                .map(attempt -> attempt.error())
                                .toList(),
                        fixture.store.eventsFor(accepted.runId()),
                        fixture.store.steps(accepted.runId()),
                        fixture.store.toolCalls(accepted.runId()))
                .isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(toolCalls).hasValue(0);
        assertThat(fixture.store.toolCalls(accepted.runId()).getFirst().status().name())
                .isEqualTo("DENIED");
        assertThat(fixture.store.messages(accepted.runId()).stream()
                        .flatMap(message -> message.contents().stream())
                        .map(Object::toString))
                .anyMatch(text -> text.contains("rejected by the operator"));
        assertThat(fixture.store.messages(accepted.runId()))
                .anyMatch(message -> message.metadata().containsKey("interactionResponseType")
                        && message.visibility() == MessageVisibility.INTERNAL);
        assertThat(resumedModelRequest.get().messages())
                .extracting(message -> message.role())
                .containsSequence(ModelMessageRole.ASSISTANT, ModelMessageRole.TOOL);
    }

    private static Fixture fixture(AgentChatModel model) {
        return fixture(model, builder -> builder);
    }

    private static Fixture fixture(
            AgentChatModel model, java.util.function.UnaryOperator<RuntimeCoreBuilder> customizer) {
        ManualExecutionScheduler scheduler = new ManualExecutionScheduler();
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        InMemoryInteractionPort interactions = new InMemoryInteractionPort();
        AtomicInteger sequence = new AtomicInteger();
        IdentifierGenerator ids = () -> "id-" + sequence.incrementAndGet();
        TimeProvider time = () -> Instant.parse("2026-07-21T00:00:00Z");
        RuntimeCoreBuilder builder = new RuntimeCoreBuilder()
                .registerChatModel("openai-compatible", "1.0.0", model)
                .scheduler(scheduler)
                .store(store)
                .interactions(interactions)
                .identifierGenerator(ids)
                .timeProvider(time);
        DefaultAgentRuntime runtime = customizer.apply(builder).build();
        return new Fixture(runtime, scheduler, store, interactions);
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
                "test objective",
                List.of(),
                RuntimeOverrides.NONE);
    }

    private static RuntimeCommand command(String runId, RuntimeCommandType type, String key) {
        return new RuntimeCommand(
                new RuntimeCommandId("command-" + key),
                new io.haifa.agent.core.run.AgentRunId(runId),
                type,
                RuntimeCommandArguments.NONE,
                key,
                Instant.parse("2026-07-21T00:00:00Z"));
    }

    private record Fixture(
            DefaultAgentRuntime runtime,
            ManualExecutionScheduler scheduler,
            InMemoryRuntimeStore store,
            InMemoryInteractionPort interactions) {}
}
