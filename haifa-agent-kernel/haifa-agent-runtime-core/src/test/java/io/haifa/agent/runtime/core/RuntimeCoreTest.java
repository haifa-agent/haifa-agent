package io.haifa.agent.runtime.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.error.AgentErrorCode;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.plan.AgentPlan;
import io.haifa.agent.core.plan.AgentPlanId;
import io.haifa.agent.core.plan.TodoItem;
import io.haifa.agent.core.plan.TodoItemId;
import io.haifa.agent.core.plan.TodoPriority;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.core.run.AgentRunOutcome;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.run.AgentRunType;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.step.AgentStepType;
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
import io.haifa.agent.runtime.api.RunInputId;
import io.haifa.agent.runtime.api.RunInputReceiptStatus;
import io.haifa.agent.runtime.api.RunInputSubmission;
import io.haifa.agent.runtime.api.RuntimeApiErrorCode;
import io.haifa.agent.runtime.api.RuntimeCommand;
import io.haifa.agent.runtime.api.RuntimeCommandArguments;
import io.haifa.agent.runtime.api.RuntimeCommandId;
import io.haifa.agent.runtime.api.RuntimeCommandStatus;
import io.haifa.agent.runtime.api.RuntimeCommandType;
import io.haifa.agent.runtime.api.RuntimeContractException;
import io.haifa.agent.runtime.api.RuntimeOverrides;
import io.haifa.agent.runtime.core.bootstrap.DefaultResolvedModelSnapshots;
import io.haifa.agent.runtime.core.bootstrap.ResolvedProfile;
import io.haifa.agent.runtime.core.bootstrap.RuntimeControlOptions;
import io.haifa.agent.runtime.core.decision.FinalAnswerDecision;
import io.haifa.agent.runtime.core.decision.ToolCallDecision;
import io.haifa.agent.runtime.core.decision.ToolRequest;
import io.haifa.agent.runtime.core.execution.ManualExecutionScheduler;
import io.haifa.agent.runtime.core.input.InMemoryRunInputPort;
import io.haifa.agent.runtime.core.interaction.InMemoryInteractionPort;
import io.haifa.agent.runtime.core.interaction.ToolApprovalTarget;
import io.haifa.agent.runtime.core.retry.BackoffStrategy;
import io.haifa.agent.runtime.core.retry.RetryPolicy;
import io.haifa.agent.runtime.core.retry.RuntimeBackoffPolicy;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import io.haifa.agent.runtime.core.storage.OptimisticLockException;
import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
import io.haifa.agent.runtime.core.tool.InMemoryToolExecutionJournal;
import io.haifa.agent.runtime.core.tool.ToolJournalState;
import io.haifa.agent.runtime.core.tool.ToolPolicyDecision;
import io.haifa.agent.tool.api.ToolSchema;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RuntimeCoreTest {
    @Test
    void currentRunObjectiveAppearsOnceThroughAuthoritativeSessionHistory() {
        AtomicReference<AgentChatRequest> captured = new AtomicReference<>();
        Fixture fixture = fixture(request -> {
            captured.set(request);
            return response(finalDecision("done"));
        });

        fixture.runtime.start(request("objective-once"));
        fixture.scheduler.runAll();

        assertThat(captured.get().messages().stream()
                        .filter(message -> message.role() == ModelMessageRole.USER)
                        .filter(message -> message.content().equals("test objective"))
                        .toList())
                .singleElement();
    }

    @Test
    void appliesAcceptedSteerOnlyAtTheNextIterationSafePoint() {
        AtomicReference<AgentChatRequest> captured = new AtomicReference<>();
        Fixture fixture = fixture(request -> {
            captured.set(request);
            return response(finalDecision("steered"));
        });
        var accepted = fixture.runtime.start(request("steer-run"));
        RunInputSubmission steer = new RunInputSubmission(
                new RunInputId("input-1"),
                accepted.runId(),
                OptionalLong.of(accepted.version()),
                List.of(new TextPart("Use the revised scope", "text/plain")),
                "steer-key",
                Instant.parse("2026-07-21T00:00:00Z"));

        assertThat(fixture.runtime.submitInput(steer).status()).isEqualTo(RunInputReceiptStatus.ACCEPTED);
        assertThat(fixture.runtime.submitInput(steer).status()).isEqualTo(RunInputReceiptStatus.DUPLICATE);
        assertThat(captured.get()).isNull();

        fixture.scheduler.runAll();

        assertThat(captured.get().messages()).anySatisfy(message -> {
            assertThat(message.role()).isEqualTo(ModelMessageRole.USER);
            assertThat(message.content()).contains("Use the revised scope");
        });
        assertThat(fixture.runInputs.find(steer.inputId()).orElseThrow().status())
                .isEqualTo(RunInputReceiptStatus.APPLIED);
        assertThat(fixture.store.eventsFor(accepted.runId()))
                .extracting(event -> event.type())
                .contains("run.input.accepted", "run.input.applied");
    }

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
        var events = fixture.store.eventsFor(accepted.runId());
        assertThat(fixture.store.pending()).allSatisfy(message -> {
            assertThat(message.schemaVersion()).isEqualTo("1");
            assertThat(events).anySatisfy(event -> {
                assertThat(event.runId()).isEqualTo(message.runId());
                assertThat(event.sequence()).isEqualTo(message.sequence());
                assertThat(event.type()).isEqualTo(message.type());
            });
        });
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
        assertThat(fixture.journal.state(
                        accepted.runId(),
                        fixture.store.toolCalls(accepted.runId()).getFirst().idempotencyKey()))
                .contains(ToolJournalState.COMPLETED);
    }

    @Test
    void finalizeOnlyRemovesToolDefinitionsAfterTheFrozenCollectionThreshold() {
        ToolRequest first =
                toolRequest("finalize-1", "echo", "1.0.0", new ToolArguments("echo.input", "1.0", Map.of()));
        ToolRequest second =
                toolRequest("finalize-2", "echo", "1.0.0", new ToolArguments("echo.input", "1.0", Map.of()));
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicReference<AgentChatRequest> finalRequest = new AtomicReference<>();
        AgentChatModel model = request -> {
            if (modelCalls.getAndIncrement() == 0) {
                assertThat(request.tools())
                        .extracting(ModelToolSpecification::name)
                        .containsExactly("echo");
                return response(new ToolCallDecision(List.of(first, second)));
            }
            finalRequest.set(request);
            return response(finalDecision("finalized"));
        };
        Fixture fixture = fixture(model, builder -> TestToolPlatform.install(
                        builder,
                        "echo",
                        "1.0.0",
                        "echo.input",
                        false,
                        request -> new ToolResult(true, "ok", Map.of(), List.of(), List.of(), false))
                .profiles((id, overrides) -> new ResolvedProfile(
                        id,
                        "1.0.0",
                        AgentRunType.CHAT,
                        new AgentRunBudget(10_000, 10_000, 10_000, 4, 4, 0, "USD", 0),
                        new AgentRunLimits(4, 0, 1, 60_000, 60_000),
                        DefaultResolvedModelSnapshots.deepSeekV4Pro(),
                        Map.of(),
                        Map.of(RuntimeControlOptions.FINALIZE_AFTER_TOOL_CALLS, 2))));

        var accepted = fixture.runtime.start(request("finalize-only"));
        fixture.scheduler.runAll();

        assertThat(fixture.runtime.find(accepted.runId()).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(finalRequest.get().tools()).isEmpty();
        assertThat(finalRequest.get().options()).doesNotContainKey(RuntimeControlOptions.FINALIZE_AFTER_TOOL_CALLS);
        assertThat(finalRequest.get().messages()).anySatisfy(message -> assertThat(message.content())
                .contains("FINALIZE_ONLY", "No more Tools", "DSML", "not a final answer"));
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
    void schemaRejectionReturnsAValueFreeRepairHintToTheModel() {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicReference<AgentChatRequest> repairRequest = new AtomicReference<>();
        ToolRequest invalid = toolRequest(
                "invalid-mode", "echo", "1.0.0", new ToolArguments("echo.input", "1.0", Map.of("mode", "COMMAND")));
        AgentChatModel model = request -> {
            if (modelCalls.getAndIncrement() == 0) {
                return response(new ToolCallDecision(List.of(invalid)));
            }
            repairRequest.set(request);
            return response(finalDecision("repaired"));
        };
        Map<String, Object> inputSchema = Map.of(
                "$schema",
                ToolSchema.DRAFT_2020_12,
                "type",
                "object",
                "properties",
                Map.of("mode", Map.of("type", "string", "enum", List.of("SCRIPT"))),
                "required",
                List.of("mode"),
                "additionalProperties",
                false);
        Fixture fixture = fixture(
                model,
                builder -> TestToolPlatform.installWithInputSchema(
                        builder,
                        "echo",
                        "1.0.0",
                        "echo.input",
                        inputSchema,
                        request -> new ToolResult(true, "unexpected", Map.of(), List.of(), List.of(), false)));

        var accepted = fixture.runtime.start(request("safe-schema-repair"));
        fixture.scheduler.runAll();

        assertThat(fixture.runtime.find(accepted.runId()).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(fixture.store.toolCalls(accepted.runId()))
                .singleElement()
                .satisfies(call -> assertThat(call.status().name()).isEqualTo("CANCELLED"));
        assertThat(repairRequest.get().messages())
                .filteredOn(message -> message.role() == ModelMessageRole.TOOL)
                .extracting(message -> message.content())
                .singleElement()
                .asString()
                .contains("Repair the tool arguments", "$/mode", "allowed by the selected mode")
                .doesNotContain("COMMAND");
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

        var staleResume = new ResumeAgentRunRequest(
                "resume-stale", accepted.runId(), Optional.empty(), OptionalLong.of(999), List.of());
        assertThatThrownBy(() -> fixture.runtime.resume(staleResume))
                .isInstanceOfSatisfying(RuntimeContractException.class, exception -> assertThat(exception.code())
                        .isEqualTo(RuntimeApiErrorCode.RUN_VERSION_CONFLICT));

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
        RuntimeCommand staleCommand = new RuntimeCommand(
                new RuntimeCommandId("command-cancel-stale"),
                accepted.runId(),
                RuntimeCommandType.CANCEL,
                RuntimeCommandArguments.NONE,
                OptionalLong.of(999),
                "cancel-stale",
                Instant.parse("2026-07-21T00:00:00Z"));
        assertThatThrownBy(() -> fixture.runtime.command(staleCommand))
                .isInstanceOfSatisfying(RuntimeContractException.class, exception -> assertThat(exception.code())
                        .isEqualTo(RuntimeApiErrorCode.RUN_VERSION_CONFLICT));

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
    void startRejectsReuseOfIdempotencyKeyForDifferentRequest() {
        Fixture fixture = fixture(model(finalDecision("done")));
        fixture.runtime.start(request("same-key"));
        AgentRunRequest changed = new AgentRunRequest(
                "same-key",
                new AgentDefinitionId("test-agent"),
                Optional.empty(),
                "test-profile",
                new AgentSessionId("session-1"),
                Optional.empty(),
                "different objective",
                List.of(),
                RuntimeOverrides.NONE);

        assertThatThrownBy(() -> fixture.runtime.start(changed))
                .isInstanceOfSatisfying(RuntimeContractException.class, exception -> assertThat(exception.code())
                        .isEqualTo(RuntimeApiErrorCode.IDEMPOTENCY_CONFLICT));
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
                            request.observer().dispatched();
                            request.observer().acknowledged();
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
    void failedDispatchedToolStillCompletesProtocolForTheNextRun() {
        ToolRequest request =
                toolRequest("uncertain", "write", "1.0.0", new ToolArguments("write.input", "1.0", Map.of()));
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicReference<AgentChatRequest> nextRunRequest = new AtomicReference<>();
        AgentChatModel model = chatRequest -> {
            if (modelCalls.incrementAndGet() == 1) {
                return response(new ToolCallDecision(List.of(request)));
            }
            nextRunRequest.set(chatRequest);
            return response(finalDecision("next run completed"));
        };
        Fixture fixture = fixture(
                model,
                builder -> TestToolPlatform.install(builder, "write", "1.0.0", "write.input", true, invocation -> {
                    invocation.observer().dispatched();
                    throw new io.haifa.agent.tool.api.ToolInvocationException(
                            "SANDBOX_PROCESS_FAILED",
                            io.haifa.agent.tool.api.ToolDispatchState.OUTCOME_UNKNOWN,
                            "sandbox process failed after dispatch");
                }));

        var failed = fixture.runtime.start(request("failed-tool-run"));
        fixture.scheduler.runAll();

        assertThat(fixture.runtime.find(failed.runId()).orElseThrow().status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(fixture.store.toolCalls(failed.runId())).singleElement().satisfies(call -> {
            assertThat(call.status().name()).isEqualTo("FAILED");
            var error = call.error().orElseThrow().error();
            assertThat(error.code()).isEqualTo(AgentErrorCode.TOOL_OUTCOME_UNKNOWN);
            assertThat(error.details())
                    .containsEntry("failureCode", "SANDBOX_PROCESS_FAILED")
                    .containsEntry("dispatchState", "OUTCOME_UNKNOWN");
        });
        assertThat(fixture.journal.state(
                        failed.runId(),
                        fixture.store.toolCalls(failed.runId()).getFirst().idempotencyKey()))
                .contains(ToolJournalState.OUTCOME_UNKNOWN);

        var next = fixture.runtime.start(request("next-run"));
        fixture.scheduler.runAll();

        assertThat(fixture.runtime.find(next.runId()).orElseThrow().status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(nextRunRequest.get().messages())
                .anyMatch(message -> message.role() == ModelMessageRole.ASSISTANT
                        && message.toolCalls().stream()
                                .anyMatch(call ->
                                        call.providerCorrelationId().value().equals("provider-uncertain")))
                .anyMatch(message -> message.role() == ModelMessageRole.TOOL
                        && message.providerCorrelationId().orElseThrow().value().equals("provider-uncertain")
                        && message.content().equals("Tool outcome could not be determined"));
    }

    @Test
    void preservesKnownNotDispatchedToolFailureThroughTheAttemptBoundary() {
        ToolRequest request = toolRequest(
                "observer-unavailable", "write", "1.0.0", new ToolArguments("write.input", "1.0", Map.of()));
        Fixture fixture = fixture(
                model(new ToolCallDecision(List.of(request))),
                builder -> TestToolPlatform.install(builder, "write", "1.0.0", "write.input", true, invocation -> {
                    throw new io.haifa.agent.tool.api.ToolInvocationException(
                            "WORKSPACE_CHANGE_OBSERVER_UNAVAILABLE",
                            io.haifa.agent.tool.api.ToolDispatchState.NOT_DISPATCHED,
                            "workspace change observation could not be established before execution");
                }));

        var failed = fixture.runtime.start(request("observer-unavailable"));
        fixture.scheduler.runAll();

        assertThat(fixture.runtime.find(failed.runId()).orElseThrow()).satisfies(run -> {
            assertThat(run.status()).isEqualTo(AgentRunStatus.FAILED);
            assertThat(run.error().orElseThrow().code())
                    .isEqualTo(AgentErrorCode.WORKSPACE_CHANGE_OBSERVER_UNAVAILABLE);
        });
        assertThat(fixture.store.toolCalls(failed.runId())).singleElement().satisfies(call -> assertThat(
                        call.error().orElseThrow().error().code())
                .isEqualTo(AgentErrorCode.WORKSPACE_CHANGE_OBSERVER_UNAVAILABLE));
    }

    @Test
    void projectsUnknownStableProviderFailureCodeWithoutReplacingItWithTheGenericRunCode() {
        ToolRequest request =
                toolRequest("sandbox-provision", "write", "1.0.0", new ToolArguments("write.input", "1.0", Map.of()));
        Fixture fixture = fixture(
                model(new ToolCallDecision(List.of(request))),
                builder -> TestToolPlatform.install(builder, "write", "1.0.0", "write.input", true, invocation -> {
                    throw new io.haifa.agent.tool.api.ToolInvocationException(
                            "SANDBOX_PROVISION_FAILED",
                            io.haifa.agent.tool.api.ToolDispatchState.NOT_DISPATCHED,
                            "sandbox provisioning failed before execution");
                }));

        var failed = fixture.runtime.start(request("sandbox-provision"));
        fixture.scheduler.runAll();

        assertThat(fixture.runtime
                        .find(failed.runId())
                        .orElseThrow()
                        .error()
                        .orElseThrow()
                        .code())
                .isEqualTo(AgentErrorCode.TOOL_INVOCATION_FAILED);
        assertThat(fixture.store.eventsFor(failed.runId()))
                .filteredOn(event -> event.type().equals("tool.failed"))
                .singleElement()
                .satisfies(event -> assertThat(event.data()).containsEntry("reasonCode", "SANDBOX_PROVISION_FAILED"));
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
        AtomicBoolean owned = new AtomicBoolean(true);
        ToolRequest progress =
                toolRequest("progress", "read", "1.0.0", new ToolArguments("read.input", "1.0", Map.of()));
        AgentChatModel model = request -> {
            int call = calls.incrementAndGet();
            if (call == 1) return response(new ToolCallDecision(List.of(progress)));
            if (call == 2) throw new AssertionError("simulated process loss");
            return response(finalDecision("recovered"));
        };
        Fixture fixture = fixture(model, builder -> TestToolPlatform.install(
                        builder,
                        "read",
                        "1.0.0",
                        "read.input",
                        false,
                        request -> new ToolResult(true, "progress", Map.of(), List.of(), List.of(), false))
                .executionOwnership(attempt -> owned.get() || attempt.attemptNumber() > 1));
        var accepted = fixture.runtime.start(request("recover"));

        assertThatThrownBy(fixture.scheduler::runNext).isInstanceOf(AssertionError.class);
        assertThat(fixture.store.find(accepted.runId()).orElseThrow().status()).isEqualTo(AgentRunStatus.RUNNING);
        assertThat(fixture.store.checkpointsFor(accepted.runId())).isNotEmpty();

        owned.set(false);
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

        var plan = fixture.runtime.plan(accepted.runId()).orElseThrow();
        assertThat(plan.id()).isEqualTo("plan-1");
        assertThat(plan.runId()).isEqualTo(accepted.runId().value());
        assertThat(plan.items()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo("todo-1");
            assertThat(item.title()).isEqualTo("verify");
            assertThat(item.status()).isEqualTo("PENDING");
        });
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
                                "approved",
                                "write",
                                "1.0.0",
                                new ToolArguments("write.input", "1.0", Map.of("v", 1, "apiKey", "sk-fake-secret")))))
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
        var publicInteraction =
                fixture.runtime.pendingInteraction(accepted.runId()).orElseThrow();
        assertThat(publicInteraction.safePrompt()).doesNotContain("sk-fake-secret", "apiKey");
        assertThat(publicInteraction.target().safeSummary()).doesNotContain("sk-fake-secret", "apiKey");
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
        assertThat(fixture.store.eventsFor(accepted.runId()))
                .extracting(io.haifa.agent.runtime.core.storage.RuntimeEvent::type)
                .contains(
                        "policy.decision.made",
                        "approval.requested",
                        "approval.authority.verified",
                        "approval.target.validated",
                        "approval.responded");
    }

    @Test
    void multipleApprovalRequiredToolsResumeSequentiallyWithoutRepeatingTheModelCall() {
        AtomicInteger modelCalls = new AtomicInteger();
        List<Integer> executed = new ArrayList<>();
        ToolRequest first =
                toolRequest("first-write", "write", "1.0.0", new ToolArguments("write.input", "1.0", Map.of("v", 1)));
        ToolRequest second =
                toolRequest("second-write", "write", "1.0.0", new ToolArguments("write.input", "1.0", Map.of("v", 2)));
        AgentChatModel model = ignored -> response(
                modelCalls.incrementAndGet() == 1
                        ? new ToolCallDecision(List.of(first, second))
                        : finalDecision("both writes completed"));
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
                            executed.add((Integer) request.arguments().values().get("v"));
                            return new ToolResult(true, "written", Map.of(), List.of(), List.of(), false);
                        }));

        var accepted = fixture.runtime.start(request("sequential-approvals"));
        fixture.scheduler.runAll();
        var firstApproval = fixture.interactions.pending(accepted.runId()).orElseThrow();

        fixture.runtime.respond(new InteractionResponse(
                new InteractionResponseId("first-response"),
                firstApproval.id(),
                accepted.runId(),
                InteractionResponseType.APPROVE,
                List.of(),
                "first-approval-key",
                Instant.parse("2026-07-21T00:00:00Z")));
        fixture.scheduler.runAll();

        assertThat(fixture.runtime.find(accepted.runId()).orElseThrow().status())
                .as(
                        "attempts=%s steps=%s calls=%s",
                        fixture.store.attemptsFor(accepted.runId()).stream()
                                .map(attempt -> attempt.error())
                                .toList(),
                        fixture.store.steps(accepted.runId()),
                        fixture.store.toolCalls(accepted.runId()))
                .isEqualTo(AgentRunStatus.WAITING_APPROVAL);
        assertThat(executed).containsExactly(1);
        var secondApproval = fixture.interactions.pending(accepted.runId()).orElseThrow();
        assertThat(secondApproval.id()).isNotEqualTo(firstApproval.id());

        fixture.runtime.respond(new InteractionResponse(
                new InteractionResponseId("second-response"),
                secondApproval.id(),
                accepted.runId(),
                InteractionResponseType.APPROVE,
                List.of(),
                "second-approval-key",
                Instant.parse("2026-07-21T00:00:00Z")));
        fixture.scheduler.runAll();

        assertThat(fixture.runtime.find(accepted.runId()).orElseThrow().status())
                .as(
                        "attempts=%s steps=%s calls=%s",
                        fixture.store.attemptsFor(accepted.runId()).stream()
                                .map(attempt -> attempt.error())
                                .toList(),
                        fixture.store.steps(accepted.runId()),
                        fixture.store.toolCalls(accepted.runId()))
                .isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(executed).containsExactly(1, 2);
        assertThat(modelCalls).hasValue(2);
        assertThat(fixture.store.attemptsFor(accepted.runId())).hasSize(3);
    }

    @Test
    void failedApprovedToolPreservesSpecificErrorAndCancelsUnstartedSibling() {
        AtomicInteger invocations = new AtomicInteger();
        ToolRequest first =
                toolRequest("first-write", "write", "1.0.0", new ToolArguments("write.input", "1.0", Map.of("v", 1)));
        ToolRequest second =
                toolRequest("second-write", "write", "1.0.0", new ToolArguments("write.input", "1.0", Map.of("v", 2)));
        Fixture fixture = fixture(
                model(new ToolCallDecision(List.of(first, second))),
                builder -> TestToolPlatform.install(
                        builder,
                        "write",
                        "1.0.0",
                        "write.input",
                        true,
                        ToolPolicyDecision.REQUIRE_APPROVAL,
                        invocation -> {
                            invocations.incrementAndGet();
                            invocation.observer().dispatched();
                            throw new IllegalStateException("provider failed after dispatch");
                        }));

        var accepted = fixture.runtime.start(request("failed-approved-tool"));
        fixture.scheduler.runAll();
        var approval = fixture.interactions.pending(accepted.runId()).orElseThrow();
        fixture.runtime.respond(new InteractionResponse(
                new InteractionResponseId("failure-response"),
                approval.id(),
                accepted.runId(),
                InteractionResponseType.APPROVE,
                List.of(),
                "failure-approval-key",
                Instant.parse("2026-07-21T00:00:00Z")));
        fixture.scheduler.runAll();

        assertThat(invocations).hasValue(1);
        assertThat(fixture.runtime.find(accepted.runId()).orElseThrow()).satisfies(run -> {
            assertThat(run.status()).isEqualTo(AgentRunStatus.FAILED);
            assertThat(run.error().orElseThrow().code()).isEqualTo(AgentErrorCode.TOOL_OUTCOME_UNKNOWN);
        });
        assertThat(fixture.store.toolCalls(accepted.runId()))
                .extracting(call -> call.status().name())
                .containsExactly("FAILED", "CANCELLED");
        assertThat(fixture.store.toolCalls(accepted.runId()).get(1).startedAt()).isEmpty();
        assertThat(fixture.store.steps(accepted.runId()))
                .filteredOn(step -> step.type() == AgentStepType.TOOL_EXECUTION)
                .extracting(step -> step.status().name())
                .containsExactly("FAILED", "CANCELLED");
        assertThat(fixture.store.eventsFor(accepted.runId())).anySatisfy(event -> {
            assertThat(event.type()).isEqualTo("tool.cancelled");
            assertThat(event.data()).containsEntry("reasonCode", "SIBLING_TOOL_FAILED");
        });
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

    @Test
    void oversizedLegacyApprovalPromptStillProjectsAsABoundedPublicInteraction() {
        String oversizedPrompt = "Approve execution\nFull content:\n" + "Write-Output 'test'\n".repeat(300);
        AgentChatModel model = model(new ToolCallDecision(List.of(toolRequest(
                "legacy-approval",
                "write",
                "1.0.0",
                new ToolArguments("write.input", "1.0", Map.of("value", "test"))))));
        Fixture fixture = fixture(model, builder -> TestToolPlatform.install(
                        builder,
                        "write",
                        "1.0.0",
                        "write.input",
                        true,
                        ToolPolicyDecision.REQUIRE_APPROVAL,
                        request -> new ToolResult(true, "written", Map.of(), List.of(), List.of(), false))
                .toolApprovalPrompts((binding, call, reauthentication) -> oversizedPrompt));

        var accepted = fixture.runtime.start(request("legacy-oversized-approval"));
        fixture.scheduler.runAll();

        assertThat(fixture.interactions.pending(accepted.runId()).orElseThrow().prompt())
                .hasSizeGreaterThan(2_048);
        assertThat(fixture.runtime
                        .pendingInteraction(accepted.runId())
                        .orElseThrow()
                        .safePrompt())
                .hasSizeLessThanOrEqualTo(2_048)
                .contains("Approve execution", "Prompt truncated for safe display", "original length=");
    }

    private static Fixture fixture(AgentChatModel model) {
        return fixture(model, builder -> builder);
    }

    private static Fixture fixture(
            AgentChatModel model, java.util.function.UnaryOperator<RuntimeCoreBuilder> customizer) {
        ManualExecutionScheduler scheduler = new ManualExecutionScheduler();
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        InMemoryInteractionPort interactions = new InMemoryInteractionPort();
        InMemoryRunInputPort runInputs = new InMemoryRunInputPort();
        InMemoryToolExecutionJournal journal = new InMemoryToolExecutionJournal();
        AtomicInteger sequence = new AtomicInteger();
        IdentifierGenerator ids = () -> "id-" + sequence.incrementAndGet();
        TimeProvider time = () -> Instant.parse("2026-07-21T00:00:00Z");
        RuntimeCoreBuilder builder = new RuntimeCoreBuilder()
                .registerChatModel("openai-compatible", "1.0.0", model)
                .scheduler(scheduler)
                .persistence(RuntimePersistencePorts.inMemory(store, journal, interactions))
                .runInputs(runInputs)
                .identifierGenerator(ids)
                .timeProvider(time);
        DefaultAgentRuntime runtime = customizer.apply(builder).build();
        return new Fixture(runtime, scheduler, store, interactions, journal, runInputs);
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
            InMemoryInteractionPort interactions,
            InMemoryToolExecutionJournal journal,
            InMemoryRunInputPort runInputs) {}
}
