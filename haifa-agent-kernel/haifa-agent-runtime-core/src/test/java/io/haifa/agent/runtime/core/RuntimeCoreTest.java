package io.haifa.agent.runtime.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.plan.AgentPlan;
import io.haifa.agent.core.plan.AgentPlanId;
import io.haifa.agent.core.plan.TodoItem;
import io.haifa.agent.core.plan.TodoItemId;
import io.haifa.agent.core.plan.TodoPriority;
import io.haifa.agent.core.run.AgentRunOutcome;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.run.AgentRunUsageDelta;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.InteractionRequestId;
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
import io.haifa.agent.runtime.core.decision.ContinueDecision;
import io.haifa.agent.runtime.core.decision.DelegationDecision;
import io.haifa.agent.runtime.core.decision.FinalAnswerDecision;
import io.haifa.agent.runtime.core.decision.InteractionDecision;
import io.haifa.agent.runtime.core.decision.ToolCallDecision;
import io.haifa.agent.runtime.core.decision.ToolRequest;
import io.haifa.agent.runtime.core.execution.ManualExecutionScheduler;
import io.haifa.agent.runtime.core.model.ModelClient;
import io.haifa.agent.runtime.core.model.ModelResponse;
import io.haifa.agent.runtime.core.retry.BackoffStrategy;
import io.haifa.agent.runtime.core.retry.RetryPolicy;
import io.haifa.agent.runtime.core.retry.RuntimeBackoffPolicy;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import io.haifa.agent.runtime.core.storage.OptimisticLockException;
import io.haifa.agent.runtime.core.tool.ToolDefinition;
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
                new ToolRequest("echo", "1.0", new ToolArguments("echo.input", "1.0", Map.of("v", 1)), "tool-1");
        ToolRequest second =
                new ToolRequest("echo", "1.0", new ToolArguments("echo.input", "1.0", Map.of("v", 2)), "tool-2");
        List<Integer> order = new ArrayList<>();
        Fixture fixture = fixture(
                model(new ToolCallDecision(List.of(first, second)), finalDecision("tools done")),
                builder -> builder.registerTool(new ToolDefinition("echo", "1.0", "echo.input", false))
                        .toolExecutor((run, definition, request) -> {
                            order.add((Integer) request.arguments().values().get("v"));
                            return new ToolResult(
                                    true, "echoed", request.arguments().values(), List.of(), List.of(), false);
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
    void rejectedToolArgumentsReturnToTheModelForBoundedRepair() {
        ToolRequest invalid =
                new ToolRequest("echo", "1.0", new ToolArguments("wrong.schema", "1.0", Map.of()), "bad-tool");
        Fixture fixture = fixture(
                model(new ToolCallDecision(List.of(invalid)), finalDecision("repaired")),
                builder -> builder.registerTool(new ToolDefinition("echo", "1.0", "echo.input", false))
                        .toolExecutor((run, definition, request) ->
                                new ToolResult(true, "unused", Map.of(), List.of(), List.of(), false)));
        var accepted = fixture.runtime.start(request("repair"));
        fixture.scheduler.runAll();

        assertThat(fixture.runtime.find(accepted.runId()).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(fixture.store.steps(accepted.runId()))
                .anyMatch(step -> step.status().name().equals("FAILED"));
        assertThat(fixture.store.toolCalls(accepted.runId()))
                .allMatch(call -> call.status().name().equals("CANCELLED"));
    }

    @Test
    void pausesOnlyAtASafePointAndResumeCreatesANewAttempt() {
        AtomicReference<DefaultAgentRuntime> runtime = new AtomicReference<>();
        AtomicReference<String> runId = new AtomicReference<>();
        Queue<ModelResponse> decisions = new ArrayDeque<>();
        ModelClient model = request -> {
            if (decisions.isEmpty()) {
                runtime.get().command(command(runId.get(), RuntimeCommandType.PAUSE, "pause-1"));
                return new ModelResponse(new ContinueDecision("checkpoint me"), 1, 1, 0);
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

        decisions.add(new ModelResponse(finalDecision("resumed"), 1, 1, 0));
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
    void interactionWaitsForInputAndThenResumes() {
        Fixture fixture = fixture(
                model(new InteractionDecision("clarification", "Which scope?", false), finalDecision("answered")));
        var accepted = fixture.runtime.start(request("interaction"));
        fixture.scheduler.runAll();
        assertThat(fixture.runtime.find(accepted.runId()).orElseThrow().status())
                .isEqualTo(AgentRunStatus.WAITING_INTERACTION);

        String interactionRequestId = fixture.store
                .find(accepted.runId())
                .orElseThrow()
                .waitingFor()
                .orElseThrow()
                .interactionRequestId();
        fixture.runtime.respond(new InteractionResponse(
                new InteractionResponseId("response-1"),
                new InteractionRequestId(interactionRequestId),
                accepted.runId(),
                InteractionResponseType.CLARIFY,
                List.of(new TextPart("all modules", "plain")),
                "interaction-response-1",
                Instant.parse("2026-07-21T00:00:00Z")));
        fixture.scheduler.runAll();
        assertThat(fixture.runtime.find(accepted.runId()).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);
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
    void loopGuardFailsRepeatedNoProgressAndStoreEnforcesOptimisticLocking() {
        Fixture fixture = fixture(request -> new ModelResponse(new ContinueDecision("same"), 0, 0, 0));
        var accepted = fixture.runtime.start(request("loop"));
        fixture.scheduler.runAll();
        assertThat(fixture.runtime.find(accepted.runId()).orElseThrow().status())
                .isEqualTo(AgentRunStatus.FAILED);

        var run = fixture.store.find(accepted.runId()).orElseThrow();
        assertThatThrownBy(() -> fixture.store.save(run, run.version() - 1))
                .isInstanceOf(OptimisticLockException.class);
    }

    @Test
    void retriesModelsAndReadOnlyToolsButNeverAutomaticallyReplaysSideEffects() {
        AtomicInteger modelCalls = new AtomicInteger();
        ModelClient transientModel = request -> {
            if (modelCalls.incrementAndGet() == 1) throw new IllegalStateException("transient model failure");
            return new ModelResponse(finalDecision("retried"), 1, 1, 0);
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
                new ToolRequest("read", "1.0", new ToolArguments("read.input", "1.0", Map.of()), "read-1");
        AtomicInteger readCalls = new AtomicInteger();
        Fixture readFixture = fixture(
                model(new ToolCallDecision(List.of(readRequest)), finalDecision("read complete")),
                builder -> builder.registerTool(new ToolDefinition("read", "1.0", "read.input", false))
                        .toolRetry(new RetryPolicy(2, error -> true, BackoffStrategy.none()))
                        .toolExecutor((run, definition, request) -> {
                            if (readCalls.incrementAndGet() == 1) throw new IllegalStateException("retry read");
                            return new ToolResult(true, "read", Map.of(), List.of(), List.of(), false);
                        }));
        var readRun = readFixture.runtime.start(request("read-retry"));
        readFixture.scheduler.runAll();
        assertThat(readCalls).hasValue(2);
        assertThat(readFixture.runtime.find(readRun.runId()).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);

        ToolRequest writeRequest =
                new ToolRequest("write", "1.0", new ToolArguments("write.input", "1.0", Map.of()), "write-1");
        AtomicInteger writeCalls = new AtomicInteger();
        Fixture writeFixture =
                fixture(model(new ToolCallDecision(List.of(writeRequest))), builder -> builder.registerTool(
                                new ToolDefinition("write", "1.0", "write.input", true))
                        .toolRetry(new RetryPolicy(3, error -> true, BackoffStrategy.none()))
                        .toolExecutor((run, definition, request) -> {
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
    void rejectsDuplicateToolCallsAndDelegationBeyondTheChildBudget() {
        ToolRequest duplicate =
                new ToolRequest("echo", "1.0", new ToolArguments("echo.input", "1.0", Map.of()), "same-key");
        Fixture duplicateFixture = fixture(
                model(new ToolCallDecision(List.of(duplicate, duplicate))),
                builder -> builder.registerTool(new ToolDefinition("echo", "1.0", "echo.input", false)));
        var duplicateRun = duplicateFixture.runtime.start(request("duplicate-tool"));
        duplicateFixture.scheduler.runAll();
        assertThat(duplicateFixture
                        .runtime
                        .find(duplicateRun.runId())
                        .orElseThrow()
                        .status())
                .isEqualTo(AgentRunStatus.FAILED);
        assertThat(duplicateFixture.store.toolCalls(duplicateRun.runId())).isEmpty();

        Fixture childFixture = fixture(model(new DelegationDecision(new AgentDefinitionId("child"), "delegate")));
        var childRun = childFixture.runtime.start(request("child-budget"));
        var aggregate = childFixture.store.find(childRun.runId()).orElseThrow();
        long expectedVersion = aggregate.version();
        aggregate.recordUsage(
                new AgentRunUsageDelta(0, 0, 0, 0, 0, aggregate.budget().maxChildRuns(), 0, 0));
        childFixture.store.save(aggregate, expectedVersion);
        childFixture.scheduler.runAll();
        assertThat(childFixture.runtime.find(childRun.runId()).orElseThrow().status())
                .isEqualTo(AgentRunStatus.FAILED);
    }

    @Test
    void recoversFromAnAbandonedAttemptAtTheLatestCheckpoint() {
        AtomicInteger calls = new AtomicInteger();
        ModelClient model = request -> {
            int call = calls.incrementAndGet();
            if (call == 1) return new ModelResponse(new ContinueDecision("durable progress"), 1, 1, 0);
            if (call == 2) throw new AssertionError("simulated process loss");
            return new ModelResponse(finalDecision("recovered"), 1, 1, 0);
        };
        Fixture fixture = fixture(model);
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
        ModelClient model = request -> {
            if (calls.incrementAndGet() == 2) {
                TodoItem todo =
                        state.get().plan(runId.get()).orElseThrow().items().getFirst();
                todo.start(java.util.Set.of(), Instant.parse("2026-07-21T00:00:00Z"));
                todo.complete("verified", Instant.parse("2026-07-21T00:00:00Z"));
            }
            return new ModelResponse(
                    new FinalAnswerDecision(
                            AgentRunOutcome.PARTIAL_SUCCESS,
                            "partial",
                            "test.result",
                            "1.0",
                            Map.of("complete", false),
                            List.of(),
                            List.of("one limitation")),
                    1,
                    1,
                    0);
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
        assertThat(run.result().orElseThrow().outcome()).isEqualTo(AgentRunOutcome.PARTIAL_SUCCESS);
        assertThat(run.result().orElseThrow().warnings()).containsExactly("one limitation");
    }

    private static Fixture fixture(ModelClient model) {
        return fixture(model, builder -> builder);
    }

    private static Fixture fixture(ModelClient model, java.util.function.UnaryOperator<RuntimeCoreBuilder> customizer) {
        ManualExecutionScheduler scheduler = new ManualExecutionScheduler();
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AtomicInteger sequence = new AtomicInteger();
        IdentifierGenerator ids = () -> "id-" + sequence.incrementAndGet();
        TimeProvider time = () -> Instant.parse("2026-07-21T00:00:00Z");
        RuntimeCoreBuilder builder = new RuntimeCoreBuilder()
                .modelClient(model)
                .scheduler(scheduler)
                .store(store)
                .identifierGenerator(ids)
                .timeProvider(time);
        DefaultAgentRuntime runtime = customizer.apply(builder).build();
        return new Fixture(runtime, scheduler, store);
    }

    private static ModelClient model(io.haifa.agent.runtime.core.decision.AgentDecision... decisions) {
        Queue<io.haifa.agent.runtime.core.decision.AgentDecision> queue = new ArrayDeque<>(List.of(decisions));
        return request -> new ModelResponse(queue.remove(), 1, 1, 0);
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
            DefaultAgentRuntime runtime, ManualExecutionScheduler scheduler, InMemoryRuntimeStore store) {}
}
