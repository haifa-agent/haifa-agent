package io.haifa.agent.runtime.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.content.ContentPart;
import io.haifa.agent.core.content.ImageUrlContentPart;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.error.AgentErrorCode;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.core.run.AgentRunOutcome;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.run.AgentRunType;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolCallStatus;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.memory.api.MemoryContext;
import io.haifa.agent.memory.api.MemoryRetriever;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.EffectiveModelParameters;
import io.haifa.agent.model.api.ImageInputProfile;
import io.haifa.agent.model.api.ImageUrlPart;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelErrorCategory;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelImageSource;
import io.haifa.agent.model.api.ModelInvocationException;
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelReasoningPolicy;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelToolSpecification;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.ChildRunCapacity;
import io.haifa.agent.runtime.api.ChildRunView;
import io.haifa.agent.runtime.api.InteractionAction;
import io.haifa.agent.runtime.api.InteractionResponseId;
import io.haifa.agent.runtime.api.InteractionResponseSubmission;
import io.haifa.agent.runtime.api.RunCancellation;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPayloads;
import io.haifa.agent.runtime.api.RuntimeOverrides;
import io.haifa.agent.runtime.core.attempt.ExecutionAttemptStatus;
import io.haifa.agent.runtime.core.bootstrap.DefaultResolvedModelSnapshots;
import io.haifa.agent.runtime.core.bootstrap.ResolvedDefinition;
import io.haifa.agent.runtime.core.bootstrap.ResolvedProfile;
import io.haifa.agent.runtime.core.delegation.ChildRunCoordinator;
import io.haifa.agent.runtime.core.delegation.ChildRunResults;
import io.haifa.agent.runtime.core.delegation.DelegationPort;
import io.haifa.agent.runtime.core.delegation.DelegationTool;
import io.haifa.agent.runtime.core.execution.ExecutionScheduler;
import io.haifa.agent.runtime.core.execution.LocalExecutionScheduler;
import io.haifa.agent.runtime.core.guard.RuntimeLimitExceededException;
import io.haifa.agent.runtime.core.retry.BackoffStrategy;
import io.haifa.agent.runtime.core.retry.RetryPolicy;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
import io.haifa.agent.runtime.core.storage.RuntimeUnitOfWork;
import io.haifa.agent.runtime.core.tool.PublicToolPolicy;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Parent-child delegation through the model-visible {@code task} Tool, with stub models only. */
class ChildRunDelegationTest {
    private static final AgentDefinitionId LEAD = new AgentDefinitionId("lead-agent");
    private static final AgentDefinitionId RESEARCHER = new AgentDefinitionId("researcher");
    private static final AgentDefinitionId REVIEWER = new AgentDefinitionId("reviewer");
    private static final Duration AWAIT = Duration.ofSeconds(20);

    private final List<AutoCloseable> closeables = new ArrayList<>();

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable closeable : closeables) closeable.close();
    }

    @Test
    void failedChildAdmissionDoesNotPreventCancellation() throws Exception {
        ThreadLocal<Boolean> failAdmission = ThreadLocal.withInitial(() -> false);
        CountDownLatch failed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger submissions = new AtomicInteger();
        AtomicInteger childCalls = new AtomicInteger();
        LocalExecutionScheduler delegate = new LocalExecutionScheduler();
        closeables.add(delegate);
        ExecutionScheduler scheduler = (runId, task) -> delegate.submit(runId, () -> {
            boolean child = submissions.incrementAndGet() > 1;
            if (child) failAdmission.set(true);
            try {
                task.run();
            } catch (RuntimeException exception) {
                failure.set(exception);
                failed.countDown();
            } finally {
                failAdmission.remove();
            }
        });
        Fixture fixture = fixture(
                Options.defaults().customize(builder -> builder.scheduler(scheduler)),
                request -> {
                    if (isParent(request))
                        return hasToolResults(request) ? answer("done", 1) : taskCalls("researcher", "never started");
                    childCalls.incrementAndGet();
                    return answer("child", 1);
                },
                original -> new RuntimeUnitOfWork() {
                    @Override
                    public <T> T execute(Supplier<T> work) {
                        T result = original.execute(work);
                        if (failAdmission.get()) {
                            failAdmission.set(false);
                            throw new IllegalStateException("simulated admission commit failure");
                        }
                        return result;
                    }
                });
        AgentRunSnapshot parent = fixture.start("failed-child-admission");
        try {
            assertThat(failed.await(AWAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
            assertThat(failure.get()).hasMessage("simulated admission commit failure");
            assertThat(fixture.runtime.children(parent.runId())).singleElement().satisfies(child -> {
                assertThat(child.status()).isEqualTo(AgentRunStatus.QUEUED);
                assertThat(child.startedAt()).isEmpty();
            });
            fixture.runtime.handle(parent.runId()).cancel();
            assertThat(fixture.runtime.handle(parent.runId()).awaitCompletion(AWAIT))
                    .get()
                    .satisfies(result -> assertThat(result.status()).isEqualTo(AgentRunStatus.CANCELLED));
            assertThat(fixture.runtime.children(parent.runId()))
                    .singleElement()
                    .satisfies(child -> assertThat(child.status()).isEqualTo(AgentRunStatus.CANCELLED));
            assertThat(childCalls).hasValue(0);
        } finally {
            fixture.runtime.handle(parent.runId()).cancel();
        }
    }

    @Test
    void childWallDeadlineBeforeWatchdogTimesOutAndParentContinues() throws Exception {
        Instant start = Instant.parse("2026-10-03T00:00:00Z");
        ThreadLocal<Boolean> childExpired = ThreadLocal.withInitial(() -> false);
        AtomicReference<Throwable> diagnostic = new AtomicReference<>();
        AtomicInteger childCalls = new AtomicInteger();
        // The worker observes its deadline before the parent's watchdog: no real sleep or scheduling race.
        Fixture fixture = fixture(
                Options.defaults().childWall(1_000).customize(builder -> builder.timeProvider(
                                () -> childExpired.get() ? start.plusMillis(1_259) : start)
                        .modelRetry(new RetryPolicy(
                                2, error -> error instanceof ModelInvocationException, BackoffStrategy.none()))
                        .failureDiagnostics((context, failure) -> diagnostic.set(failure))),
                request -> {
                    if (isParent(request)) {
                        return hasToolResults(request)
                                ? answer("parent continued", 1)
                                : taskCalls("researcher", "slow request");
                    }
                    childCalls.incrementAndGet();
                    childExpired.set(true);
                    throw new ModelInvocationException(
                            ModelErrorCategory.TIMEOUT,
                            true,
                            0,
                            "timeout",
                            request.callId(),
                            "model request timed out",
                            null);
                });

        AgentRunSnapshot parent = fixture.startAndAwait("wall-before-watchdog");
        ChildRunView child = fixture.runtime.children(parent.runId()).getFirst();
        assertThat(diagnostic.get().getCause()).isInstanceOf(RuntimeLimitExceededException.class);
        RuntimeLimitExceededException limit =
                (RuntimeLimitExceededException) diagnostic.get().getCause();
        assertThat(limit.resource()).isEqualTo("wallTimeMillis");
        assertThat(limit.limit()).isEqualTo(1_000);
        assertThat(limit.used()).isEqualTo(1_259);
        assertThat(child.status())
                .as("actual retry guard resource=%s limit=%s used=%s", limit.resource(), limit.limit(), limit.used())
                .isEqualTo(AgentRunStatus.TIMEOUT);
        AgentRunSnapshot persisted = fixture.runtime.find(child.runId()).orElseThrow();
        assertThat(persisted.status()).isEqualTo(AgentRunStatus.TIMEOUT);
        assertThat(persisted.terminationReason().orElseThrow().code()).isEqualTo("WALL_TIME_EXCEEDED");
        assertThat(childTerminalEvents(fixture, parent.runId(), child.runId()))
                .containsExactly("child.run.timed-out:TIMEOUT");
        assertThat(delegationCalls(fixture, parent.runId())
                        .getFirst()
                        .result()
                        .orElseThrow()
                        .structuredData())
                .containsEntry("status", "TIMEOUT")
                .containsEntry("reasonCode", "WALL_TIME_EXCEEDED");
        assertThat(childCalls).hasValue(1);
        assertThat(parent.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(parent.result().orElseThrow().summary()).isEqualTo("parent continued");
    }

    @Test
    void twoDelegationsInOneResponseRunInParallelAndReturnSummariesUsageAndEvents() throws Exception {
        CountDownLatch bothChildrenStarted = new CountDownLatch(2);
        List<AgentChatRequest> childRequests = new CopyOnWriteArrayList<>();
        Fixture fixture = fixture(Options.defaults(), request -> {
            if (isParent(request)) {
                return hasToolResults(request)
                        ? answer("synthesis of " + toolResults(request).size() + " children", 10)
                        : taskCalls("researcher", "research alpha", "reviewer", "review beta");
            }
            childRequests.add(request);
            bothChildrenStarted.countDown();
            // Returns only when both children are inside their model call at the same time.
            if (!await(bothChildrenStarted, 10)) throw new IllegalStateException("children did not overlap");
            return answer("finding for " + brief(request), 100);
        });

        AgentRunSnapshot parent = fixture.startAndAwait("parallel");

        assertThat(parent.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(parent.result().orElseThrow().summary()).isEqualTo("synthesis of 2 children");
        List<ChildRunView> children = fixture.runtime.children(parent.runId());
        assertThat(children).hasSize(2).allSatisfy(child -> {
            assertThat(child.status()).isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(child.parentRunId()).isEqualTo(parent.runId());
            assertThat(child.startedAt()).isPresent();
            assertThat(child.completedAt()).isPresent();
        });
        assertThat(children)
                .extracting(ChildRunView::objective)
                .containsExactlyInAnyOrder("research alpha", "review beta");

        // Each Tool Result carries the child Run ID, Runtime status, summary, usage and artifact references.
        List<ToolCall> delegations = delegationCalls(fixture, parent.runId());
        assertThat(delegations).hasSize(2).allSatisfy(call -> {
            assertThat(call.status()).isEqualTo(ToolCallStatus.COMPLETED);
            ToolResult result = call.result().orElseThrow();
            assertThat(result.structuredData())
                    .containsEntry("status", "COMPLETED")
                    .containsEntry("outputPreview", result.structuredData().get("summary"))
                    .containsEntry("outputTruncated", false)
                    .containsKeys("childRunId", "summary", "usage", "artifacts", "outputSha256");
            assertThat(result.structuredData().get("outputSha256")).asString().matches("[0-9a-f]{64}");
            assertThat(result.structuredData().get("childRunId"))
                    .isEqualTo(ChildRunCoordinator.childRunId(parent.runId(), call.id())
                            .value());
            AgentRun child = fixture.store
                    .find(ChildRunCoordinator.childRunId(parent.runId(), call.id()))
                    .orElseThrow();
            ToolResult missingOutput = ChildRunResults.toolResult(child, Optional.empty());
            assertThat(missingOutput.structuredData())
                    .doesNotContainKeys("outputPreview", "outputSha256", "outputTruncated");
            assertThat(missingOutput.summary()).isEqualTo(result.summary());
        });

        // D4: the parent's usage holds only its own tokens and the child-run count.
        assertThat(parent.usage().childRuns()).isEqualTo(2);
        // One tool-call response (1 token) plus the final synthesis (10 tokens); child tokens are not merged.
        assertThat(parent.usage().modelCalls()).isEqualTo(2);
        assertThat(parent.usage().inputTokens()).isEqualTo(11);
        for (ChildRunView child : children) {
            assertThat(child.usage().inputTokens()).isEqualTo(100);
            assertThat(fixture.runtime.find(child.runId()).orElseThrow().usage().inputTokens())
                    .isEqualTo(100);
        }

        // Depth one: children never see the delegation tool and cannot delegate.
        assertThat(childRequests)
                .allSatisfy(request -> assertThat(toolNames(request)).doesNotContain(DelegationTool.NAME));

        // Parent public events carry child start and terminal events; child events are readable by child ID.
        var parentEvents = fixture.runtime.events(parent.runId(), RunEventCursor.beforeFirst(parent.runId()), 200);
        assertThat(parentEvents.items())
                .filteredOn(event -> event.eventType().equals("child.run.started"))
                .hasSize(2);
        assertThat(parentEvents.items())
                .filteredOn(event -> event.eventType().equals("child.run.completed"))
                .hasSize(2)
                .allSatisfy(event -> assertThat(((RunEventPayloads.ChildRunLifecycle) event.payload()).childRunId())
                        .isIn(children.stream()
                                .map(child -> child.runId().value())
                                .toList()));
        var childEvents = fixture.runtime.events(
                children.getFirst().runId(),
                RunEventCursor.beforeFirst(children.getFirst().runId()),
                200);
        assertThat(childEvents.items()).anyMatch(event -> event.eventType().equals("run.status.changed"));
    }

    @Test
    void retriedToolCallReattachesToItsExistingChildInsteadOfCreatingAnother() throws Exception {
        AtomicInteger childModelCalls = new AtomicInteger();
        Fixture fixture = fixture(Options.defaults(), request -> {
            if (isParent(request)) {
                return hasToolResults(request) ? answer("done", 1) : taskCalls("researcher", "research once");
            }
            childModelCalls.incrementAndGet();
            return answer("child answer", 1);
        });
        AgentRunSnapshot parent = fixture.startAndAwait("retry");
        ToolCall call = delegationCalls(fixture, parent.runId()).getFirst();
        AgentRun parentRun = fixture.store.find(parent.runId()).orElseThrow();
        List<AgentRunId> reported = new ArrayList<>();

        fixture.runtime
                .delegations()
                .executeChildren(
                        parentRun,
                        List.of(new DelegationPort.ChildRunRequest(
                                call.id(), RESEARCHER, "research once", "research once")),
                        new DelegationPort.Listener() {
                            @Override
                            public void terminal(ToolCallId toolCallId, AgentRun child) {
                                reported.add(child.id());
                            }

                            @Override
                            public void rejected(ToolCallId toolCallId, String safeReason) {
                                throw new AssertionError(safeReason);
                            }

                            @Override
                            public void notStarted(ToolCallId toolCallId) {
                                throw new AssertionError("not started");
                            }
                        });

        assertThat(reported).containsExactly(ChildRunCoordinator.childRunId(parent.runId(), call.id()));
        assertThat(fixture.runtime.children(parent.runId())).hasSize(1);
        assertThat(childModelCalls).hasValue(1);
        assertThat(fixture.store.find(parent.runId()).orElseThrow().usage().childRuns())
                .isEqualTo(1);
    }

    @Test
    void delegationsBeyondMaxParallelChildrenQueueAndAllComplete() throws Exception {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        Fixture fixture = fixture(Options.defaults().parallel(2), request -> {
            if (isParent(request)) {
                return hasToolResults(request)
                        ? answer("all done", 1)
                        : taskCalls("researcher", "one", "researcher", "two", "researcher", "three");
            }
            int now = running.incrementAndGet();
            maximum.accumulateAndGet(now, Math::max);
            sleep(200);
            running.decrementAndGet();
            return answer("result " + brief(request), 1);
        });

        AgentRunSnapshot parent = fixture.startAndAwait("queue");

        assertThat(parent.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(maximum).hasValue(2);
        assertThat(fixture.runtime.children(parent.runId())).hasSize(3).allSatisfy(child -> assertThat(child.status())
                .isEqualTo(AgentRunStatus.COMPLETED));
    }

    @Test
    void exceedingMaxChildRunsConvergesThroughTheExistingBudgetLimitedCompletion() throws Exception {
        Fixture fixture = fixture(Options.defaults().maxChildRuns(1), request -> {
            if (isParent(request)) return taskCalls("researcher", "one", "researcher", "two");
            throw new AssertionError("no child may start");
        });

        AgentRunSnapshot parent = fixture.startAndAwait("child-budget");

        assertThat(parent.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(parent.result().orElseThrow().outcome()).isEqualTo(AgentRunOutcome.PARTIAL_SUCCESS);
        assertThat(parent.result().orElseThrow().warnings()).contains("BUDGET_LIMITED:CHILD_RUNS");
        assertThat(fixture.runtime.children(parent.runId())).isEmpty();
    }

    @Test
    void waitingForAChildDoesNotCountAsParentIdleTime() throws Exception {
        Fixture fixture = fixture(Options.defaults().parentIdle(300), request -> {
            if (isParent(request)) {
                return hasToolResults(request) ? answer("patient parent", 1) : taskCalls("researcher", "slow");
            }
            sleep(900);
            return answer("slow answer", 1);
        });

        AgentRunSnapshot parent = fixture.startAndAwait("idle");

        assertThat(parent.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(fixture.runtime.children(parent.runId()).getFirst().status()).isEqualTo(AgentRunStatus.COMPLETED);
    }

    @Test
    void waitingForAChildStillCountsTowardParentWallTimeAndStopsTheChild() throws Exception {
        Instant start = Instant.parse("2026-10-08T00:00:00Z");
        AtomicReference<Instant> clock = new AtomicReference<>(start);
        CountDownLatch childStarted = new CountDownLatch(1);
        Fixture fixture = fixture(
                Options.defaults().parentWall(800).customize(builder -> builder.timeProvider(clock::get)), request -> {
                    if (isParent(request)) return taskCalls("researcher", "endless");
                    childStarted.countDown();
                    sleep(10_000);
                    return answer("too late", 1);
                });

        AgentRunSnapshot started = fixture.start("wall");
        assertThat(childStarted.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(fixture.runtime.children(started.runId())).isNotEmpty();

        clock.set(start.plusMillis(801));

        AgentRunSnapshot parent =
                fixture.runtime.handle(started.runId()).awaitCompletion(AWAIT).orElseThrow();

        assertThat(parent.status()).isEqualTo(AgentRunStatus.TIMEOUT);
        assertThat(parent.terminationReason().orElseThrow().code()).isEqualTo("WALL_TIME_EXCEEDED");
        AgentRunId child = fixture.runtime.children(parent.runId()).getFirst().runId();
        eventually(() -> fixture.store.find(child).orElseThrow().status().isTerminal());
        assertThat(fixture.store.find(child).orElseThrow().status()).isEqualTo(AgentRunStatus.CANCELLED);
        assertThat(delegationCalls(fixture, parent.runId()).getFirst().status())
                .isIn(ToolCallStatus.TIMEOUT, ToolCallStatus.CANCELLED, ToolCallStatus.FAILED);
    }

    @Test
    void childApprovalKeepsTheParentWaitingUntilTheChildRunIsApproved() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        PublicToolPolicy approvalForChildWrites = (run, binding, request) ->
                binding.alias().value().equals("write_doc") && run.parentRunId().isPresent()
                        ? TestToolPlatform.approvalRequired()
                        : TestToolPlatform.allow();
        Fixture fixture = fixture(
                Options.defaults().tools(approvalForChildWrites, invocation -> {
                    if (invocation.binding().alias().value().equals("write_doc")) writes.incrementAndGet();
                    return new ToolResult(true, "written", Map.of(), List.of(), List.of(), false);
                }),
                request -> {
                    if (isParent(request)) {
                        return hasToolResults(request) ? answer("approved work", 1) : taskCalls("reviewer", "edit");
                    }
                    return hasToolResults(request) ? answer("edited", 1) : toolCall("write_doc");
                });

        AgentRunSnapshot started = fixture.start("approval");
        eventually(() -> !fixture.runtime.children(started.runId()).isEmpty()
                && fixture.runtime.children(started.runId()).getFirst().status() == AgentRunStatus.WAITING_APPROVAL);
        AgentRunId child = fixture.runtime.children(started.runId()).getFirst().runId();
        assertThat(fixture.store.find(started.runId()).orElseThrow().status()).isEqualTo(AgentRunStatus.RUNNING);
        var pending = fixture.runtime.pendingInteraction(child).orElseThrow();
        assertThat(pending.runId()).isEqualTo(child);
        assertThat(fixture.runtime.pendingInteraction(started.runId())).isEmpty();

        fixture.runtime.respond(new InteractionResponseSubmission(
                new InteractionResponseId("approve-child"),
                pending.requestId(),
                child,
                pending.revision(),
                InteractionAction.APPROVE,
                List.of(),
                "approve-child",
                Instant.now()));

        AgentRunSnapshot parent =
                fixture.runtime.handle(started.runId()).awaitCompletion(AWAIT).orElseThrow();
        assertThat(parent.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(fixture.store.find(child).orElseThrow().status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(writes).hasValue(1);
    }

    @Test
    void childApprovalCanResumeFromTheCommittedWaitingListenerWithoutReleasingItsSlotEarly() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        AtomicBoolean responded = new AtomicBoolean();
        AtomicReference<Throwable> responseFailure = new AtomicReference<>();
        CountDownLatch responseReturned = new CountDownLatch(1);
        PublicToolPolicy approvalForChildWrites = (run, binding, request) ->
                binding.alias().value().equals("write_doc") && run.parentRunId().isPresent()
                        ? TestToolPlatform.approvalRequired()
                        : TestToolPlatform.allow();
        Fixture fixture = fixture(
                Options.defaults().parallel(1).tools(approvalForChildWrites, invocation -> {
                    if (invocation.binding().alias().value().equals("write_doc")) writes.incrementAndGet();
                    return new ToolResult(true, "written", Map.of(), List.of(), List.of(), false);
                }),
                request -> {
                    if (isParent(request)) {
                        return hasToolResults(request) ? answer("approved work", 1) : taskCalls("reviewer", "edit");
                    }
                    return hasToolResults(request) ? answer("edited", 1) : toolCall("write_doc");
                });
        fixture.runtime.addListener(snapshot -> {
            if (snapshot.status() != AgentRunStatus.WAITING_APPROVAL
                    || fixture.store
                            .find(snapshot.runId())
                            .orElseThrow()
                            .parentRunId()
                            .isEmpty()
                    || !responded.compareAndSet(false, true)) {
                return;
            }
            try {
                var pending =
                        fixture.runtime.pendingInteraction(snapshot.runId()).orElseThrow();
                fixture.runtime.respond(new InteractionResponseSubmission(
                        new InteractionResponseId("approve-child-from-listener"),
                        pending.requestId(),
                        snapshot.runId(),
                        pending.revision(),
                        InteractionAction.APPROVE,
                        List.of(),
                        "approve-child-from-listener",
                        Instant.now()));
            } catch (Throwable failure) {
                responseFailure.set(failure);
            } finally {
                responseReturned.countDown();
            }
        });

        AgentRunSnapshot started = fixture.start("approval-from-listener");

        assertThat(responseReturned.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(responseFailure.get()).isNull();
        AgentRunSnapshot parent =
                fixture.runtime.handle(started.runId()).awaitCompletion(AWAIT).orElseThrow();
        AgentRunId child = fixture.runtime.children(parent.runId()).getFirst().runId();
        assertThat(parent.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(fixture.store.find(child).orElseThrow().status()).isEqualTo(AgentRunStatus.COMPLETED);
        eventually(() -> fixture.store.attemptsFor(child).stream()
                .noneMatch(attempt -> attempt.status() == ExecutionAttemptStatus.RUNNING));
        assertThat(fixture.store.attemptsFor(child))
                .extracting(attempt -> attempt.status())
                .containsExactly(ExecutionAttemptStatus.PAUSED, ExecutionAttemptStatus.SUCCEEDED);
        assertThat(writes).hasValue(1);
    }

    @Test
    void childRunsNeitherRecallNorWriteLongTermMemory() throws Exception {
        List<String> recalledRuns = new CopyOnWriteArrayList<>();
        MemoryRetriever recording = request -> {
            recalledRuns.add(request.runId());
            return new MemoryContext(List.of(), "test-policy", "test-digest");
        };
        Fixture fixture = fixture(Options.defaults().customize(builder -> builder.memory(recording)), request -> {
            if (isParent(request)) {
                return hasToolResults(request) ? answer("done", 1) : taskCalls("researcher", "no memory");
            }
            return answer("child", 1);
        });

        AgentRunSnapshot parent = fixture.startAndAwait("memory");

        AgentRunId child = fixture.runtime.children(parent.runId()).getFirst().runId();
        assertThat(recalledRuns).contains(parent.runId().value()).doesNotContain(child.value());
        assertThat(fixture.store.memorySelection(child)).isEmpty();
    }

    @Test
    void childTimeoutAndFailureAreReturnedToTheParentWhichContinues() throws Exception {
        List<AgentChatRequest> parentRequests = new CopyOnWriteArrayList<>();
        Fixture fixture = fixture(Options.defaults().childWall(500), request -> {
            if (isParent(request)) {
                parentRequests.add(request);
                return hasToolResults(request)
                        ? answer("recovered from child outcomes", 1)
                        : taskCalls("researcher", "hang", "reviewer", "break");
            }
            if (brief(request).equals("hang")) {
                sleep(10_000);
                return answer("never", 1);
            }
            throw new ModelInvocationException(
                    ModelErrorCategory.AUTHENTICATION_FAILED,
                    false,
                    401,
                    "auth",
                    request.callId(),
                    "stub authentication failure",
                    null);
        });

        AgentRunSnapshot parent = fixture.startAndAwait("child-failures");

        assertThat(parent.status()).isEqualTo(AgentRunStatus.COMPLETED);
        Map<String, AgentRunStatus> byObjective = new ConcurrentHashMap<>();
        fixture.runtime.children(parent.runId()).forEach(child -> byObjective.put(child.objective(), child.status()));
        assertThat(byObjective)
                .containsEntry("hang", AgentRunStatus.TIMEOUT)
                .containsEntry("break", AgentRunStatus.FAILED);
        List<String> results = toolResults(parentRequests.getLast());
        assertThat(results).anyMatch(text -> text.contains("timed out (WALL_TIME_EXCEEDED)"));
        assertThat(results).anyMatch(text -> text.contains("failed (MODEL_AUTHENTICATION_FAILED)"));
        assertThat(delegationCalls(fixture, parent.runId()))
                .allSatisfy(call -> assertThat(call.status()).isEqualTo(ToolCallStatus.FAILED));
    }

    @Test
    void cancellingTheParentTerminatesRunningChildrenAndDropsQueuedOnes() throws Exception {
        CountDownLatch childStarted = new CountDownLatch(1);
        Fixture fixture = fixture(Options.defaults().parallel(1), request -> {
            if (isParent(request)) return taskCalls("researcher", "first", "researcher", "second");
            childStarted.countDown();
            sleep(10_000);
            return answer("never", 1);
        });
        AgentRunSnapshot started = fixture.start("cancel");
        assertThat(childStarted.await(10, TimeUnit.SECONDS)).isTrue();

        fixture.runtime.handle(started.runId()).cancel(RunCancellation.userRequest());

        AgentRunSnapshot parent =
                fixture.runtime.handle(started.runId()).awaitCompletion(AWAIT).orElseThrow();
        assertThat(parent.status()).isEqualTo(AgentRunStatus.CANCELLED);
        List<ChildRunView> children = fixture.runtime.children(parent.runId());
        assertThat(children).hasSize(1);
        eventually(() -> fixture.store
                .find(children.getFirst().runId())
                .orElseThrow()
                .status()
                .isTerminal());
        AgentRun child = fixture.store.find(children.getFirst().runId()).orElseThrow();
        assertThat(child.status()).isEqualTo(AgentRunStatus.CANCELLED);
        assertThat(child.terminationReason().orElseThrow().code()).isEqualTo("PARENT_CANCELLED");
        assertThat(delegationCalls(fixture, parent.runId())).hasSize(2).allSatisfy(call -> assertThat(call.status())
                .isIn(ToolCallStatus.CANCELLED, ToolCallStatus.FAILED));
    }

    @Test
    void readOnlyChildNeverSeesTheWriteToolAndPolicyStillGovernsChildToolCalls() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        List<AgentChatRequest> researcherRequests = new CopyOnWriteArrayList<>();
        List<AgentChatRequest> reviewerRequests = new CopyOnWriteArrayList<>();
        PublicToolPolicy denyChildWrites = (run, binding, request) ->
                binding.alias().value().equals("write_doc") && run.parentRunId().isPresent()
                        ? TestToolPlatform.deny()
                        : TestToolPlatform.allow();
        Fixture fixture = fixture(
                Options.defaults().tools(denyChildWrites, invocation -> {
                    if (invocation.binding().alias().value().equals("write_doc")) writes.incrementAndGet();
                    return new ToolResult(true, "ok", Map.of(), List.of(), List.of(), false);
                }),
                request -> {
                    if (isParent(request)) {
                        return hasToolResults(request)
                                ? answer("done", 1)
                                : taskCalls("researcher", "read only", "reviewer", "try write");
                    }
                    if (brief(request).equals("read only")) {
                        researcherRequests.add(request);
                        return answer("read", 1);
                    }
                    reviewerRequests.add(request);
                    return hasToolResults(request) ? answer("gave up", 1) : toolCall("write_doc");
                });

        AgentRunSnapshot parent = fixture.startAndAwait("read-only");

        assertThat(parent.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(toolNames(researcherRequests.getFirst()))
                .contains("read_doc")
                .doesNotContain("write_doc");
        assertThat(toolResults(reviewerRequests.getLast())).anyMatch(text -> text.contains("denied by policy"));
        assertThat(writes).hasValue(0);
    }

    @Test
    void childModelCannotDelegateFurtherBecauseTheToolIsNeverDisclosed() throws Exception {
        Fixture fixture = fixture(Options.defaults(), request -> {
            if (isParent(request)) {
                return hasToolResults(request) ? answer("done", 1) : taskCalls("researcher", "nested");
            }
            return taskCalls("reviewer", "grandchild");
        });

        AgentRunSnapshot parent = fixture.startAndAwait("nested");

        assertThat(parent.status()).isEqualTo(AgentRunStatus.COMPLETED);
        ChildRunView child = fixture.runtime.children(parent.runId()).getFirst();
        assertThat(child.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(fixture.runtime.children(child.runId())).isEmpty();
        AgentRun childRun = fixture.store.find(child.runId()).orElseThrow();
        assertThat(fixture.store
                        .configuration(childRun.configurationSnapshot())
                        .orElseThrow()
                        .allowedChildAgents())
                .isEmpty();
    }

    @Test
    void mixedResponseRunsDelegationsFirstAndThenOrdinaryToolsInModelOrder() throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();
        Fixture fixture = fixture(
                Options.defaults().tools((run, binding, request) -> TestToolPlatform.allow(), invocation -> {
                    order.add("tool:" + invocation.binding().alias().value());
                    return new ToolResult(true, "doc", Map.of(), List.of(), List.of(), false);
                }),
                request -> {
                    if (isParent(request)) {
                        if (hasToolResults(request)) return answer("mixed done", 1);
                        return response(List.of(
                                new ModelToolCall(
                                        new ProviderToolCallCorrelationId("mixed-read"), "read_doc", Map.of()),
                                new ModelToolCall(
                                        new ProviderToolCallCorrelationId("mixed-task"),
                                        DelegationTool.NAME,
                                        Map.of("agent", "researcher", "objective", "delegated"))));
                    }
                    sleep(200);
                    order.add("child:" + brief(request));
                    return answer("child", 1);
                });

        AgentRunSnapshot parent = fixture.startAndAwait("mixed");

        assertThat(parent.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(order).containsExactly("child:delegated", "tool:read_doc");
        // The single assistant message keeps both calls in model order with their provider correlation IDs.
        assertThat(fixture.store.messages(parent.runId()).stream()
                        .flatMap(message -> message.contents().stream())
                        .filter(io.haifa.agent.core.content.ToolCallPart.class::isInstance)
                        .map(io.haifa.agent.core.content.ToolCallPart.class::cast)
                        .map(part -> part.providerCorrelationId().value())
                        .toList())
                .containsExactly("mixed-read", "mixed-task");
    }

    @Test
    void invalidDelegationArgumentsAreRepairableToolRejections() throws Exception {
        List<AgentChatRequest> parentRequests = new CopyOnWriteArrayList<>();
        Fixture fixture = fixture(Options.defaults(), request -> {
            if (!isParent(request)) throw new AssertionError("no child may start");
            parentRequests.add(request);
            if (hasToolResults(request)) return answer("repaired", 1);
            return taskCalls("unknown-agent", "x");
        });

        AgentRunSnapshot parent = fixture.startAndAwait("invalid");

        assertThat(parent.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(toolResults(parentRequests.getLast())).singleElement().satisfies(text -> assertThat(text)
                .contains("not allowed"));
        assertThat(fixture.runtime.children(parent.runId())).isEmpty();
    }

    @Test
    void restartSettlesUnfinishedChildrenAsInterruptedWithoutReplayAndKeepsCommittedResults() throws Exception {
        AtomicBoolean crashed = new AtomicBoolean();
        Thread testThread = Thread.currentThread();
        CountDownLatch slowChildInModel = new CountDownLatch(1);
        CountDownLatch releaseSlowChild = new CountDownLatch(1);
        AtomicInteger slowChildCalls = new AtomicInteger();
        TimeProvider crashingClock = () -> {
            if (crashed.get() && Thread.currentThread() != testThread) {
                throw new AssertionError("simulated process loss");
            }
            return Instant.now();
        };
        Fixture fixture = fixture(
                Options.defaults().customize(builder -> builder.timeProvider(crashingClock)
                        .executionOwnership(attempt -> !crashed.get())),
                request -> {
                    if (isParent(request)) return taskCalls("researcher", "fast", "reviewer", "slow");
                    if (brief(request).equals("fast")) return answer("fast finding", 1);
                    slowChildCalls.incrementAndGet();
                    slowChildInModel.countDown();
                    await(releaseSlowChild, 20);
                    return answer("slow finding", 1);
                });
        AgentRunSnapshot started = fixture.start("restart");
        assertThat(slowChildInModel.await(10, TimeUnit.SECONDS)).isTrue();
        eventually(() -> delegationCalls(fixture, started.runId()).stream()
                .anyMatch(call -> call.status() == ToolCallStatus.COMPLETED));

        crashed.set(true);
        releaseSlowChild.countDown();
        sleep(1_000);
        assertThat(fixture.store.find(started.runId()).orElseThrow().status()).isEqualTo(AgentRunStatus.RUNNING);

        AgentRunSnapshot recovered = fixture.runtime.recover(started.runId());

        assertThat(recovered.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(recovered.error().orElseThrow().code()).isEqualTo(AgentErrorCode.RUNTIME_EXECUTION_INTERRUPTED);
        Map<String, AgentRun> children = new ConcurrentHashMap<>();
        fixture.runtime
                .children(started.runId())
                .forEach(child -> children.put(
                        child.objective(), fixture.store.find(child.runId()).orElseThrow()));
        assertThat(children.get("fast").status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(children.get("fast").result().orElseThrow().summary()).isEqualTo("fast finding");
        assertThat(children.get("slow").status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(children.get("slow").error().orElseThrow().code())
                .isEqualTo(AgentErrorCode.RUNTIME_EXECUTION_INTERRUPTED);
        assertThat(slowChildCalls).hasValue(1);
        assertThat(delegationCalls(fixture, started.runId()))
                .extracting(ToolCall::status)
                .containsExactlyInAnyOrder(ToolCallStatus.COMPLETED, ToolCallStatus.CANCELLED);
        // Recovery settles the lost child through its own terminal transition, which projects the parent event.
        assertThat(childTerminalEvents(
                        fixture, started.runId(), children.get("fast").id()))
                .containsExactly("child.run.completed:COMPLETED");
        assertThat(childTerminalEvents(
                        fixture, started.runId(), children.get("slow").id()))
                .containsExactly("child.run.failed:FAILED");
    }

    @Test
    void independentCoordinatorsShareThreeSlotsAndWakeWhenAnotherChildSettles() throws Exception {
        ChildRunCapacity capacity = new ChildRunCapacity(3);
        CountDownLatch threeStarted = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger executing = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        java.util.function.Function<AgentChatRequest, AgentChatResponse> model = request -> {
            if (isParent(request))
                return hasToolResults(request)
                        ? answer("done", 1)
                        : taskCalls("researcher", "first", "researcher", "second");
            maximum.accumulateAndGet(executing.incrementAndGet(), Math::max);
            threeStarted.countDown();
            try {
                awaitIgnoringInterrupts(release);
                return answer("finding", 1);
            } finally {
                executing.decrementAndGet();
            }
        };
        Fixture first = fixture(Options.defaults().customize(builder -> builder.childRunCapacity(capacity)), model);
        Fixture second = fixture(Options.defaults().customize(builder -> builder.childRunCapacity(capacity)), model);
        var parentA = first.start("shared-a");
        var parentB = second.start("shared-b");
        try {
            assertThat(threeStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(first.runtime.children(parentA.runId()).size()
                            + second.runtime.children(parentB.runId()).size())
                    .isEqualTo(3);
            assertThat(maximum).hasValue(3);
        } finally {
            release.countDown();
        }
        assertThat(first.runtime
                        .handle(parentA.runId())
                        .awaitCompletion(AWAIT)
                        .orElseThrow()
                        .status())
                .isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(second.runtime
                        .handle(parentB.runId())
                        .awaitCompletion(AWAIT)
                        .orElseThrow()
                        .status())
                .isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(first.runtime.children(parentA.runId())).hasSize(2);
        assertThat(second.runtime.children(parentB.runId())).hasSize(2);
        assertThat(maximum).hasValue(3);
    }

    @Test
    void cancellingAnotherCoordinatorsPendingParentCreatesNoChildAndLeaksNoSlot() throws Exception {
        ChildRunCapacity capacity = new ChildRunCapacity(1);
        CountDownLatch heldStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        java.util.function.Function<AgentChatRequest, AgentChatResponse> model = request -> {
            if (isParent(request)) return hasToolResults(request) ? answer("done", 1) : taskCalls("researcher", "held");
            heldStarted.countDown();
            awaitIgnoringInterrupts(release);
            return answer("finding", 1);
        };
        Fixture first = fixture(Options.defaults().customize(builder -> builder.childRunCapacity(capacity)), model);
        Fixture second = fixture(Options.defaults().customize(builder -> builder.childRunCapacity(capacity)), model);
        var held = first.start("shared-held");
        assertThat(heldStarted.await(10, TimeUnit.SECONDS)).isTrue();
        var pending = second.start("shared-pending");
        try {
            eventually(() -> !delegationCalls(second, pending.runId()).isEmpty());
            assertThat(second.runtime.children(pending.runId())).isEmpty();
            second.runtime.handle(pending.runId()).cancel(RunCancellation.userRequest());
            assertThat(second.runtime
                            .handle(pending.runId())
                            .awaitCompletion(AWAIT)
                            .orElseThrow()
                            .status())
                    .isEqualTo(AgentRunStatus.CANCELLED);
            assertThat(second.runtime.children(pending.runId())).isEmpty();
            assertThat(second.runtime
                            .events(pending.runId(), RunEventCursor.beforeFirst(pending.runId()), 200)
                            .items())
                    .noneMatch(event -> event.eventType().equals("child.run.started"));
        } finally {
            release.countDown();
        }
        assertThat(first.runtime
                        .handle(held.runId())
                        .awaitCompletion(AWAIT)
                        .orElseThrow()
                        .status())
                .isEqualTo(AgentRunStatus.COMPLETED);
        var later = second.startAndAwait("shared-later");
        assertThat(later.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(second.runtime.children(later.runId())).hasSize(1);
    }

    @Test
    void processSlotsStayTakenAfterParentCancelUntilTheChildTasksActuallyEnd() throws Exception {
        CountDownLatch heldChildrenStarted = new CountDownLatch(2);
        CountDownLatch releaseHeldChildren = new CountDownLatch(1);
        AtomicInteger executing = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        AtomicBoolean laterChildStarted = new AtomicBoolean();
        Fixture fixture =
                fixture(Options.defaults().customize(builder -> builder.maxConcurrentChildRuns(2)), request -> {
                    if (isParent(request)) {
                        if (hasToolResults(request)) return answer("parent done", 1);
                        return brief(request).equals("objective hold")
                                ? taskCalls("researcher", "held-1", "researcher", "held-2")
                                : taskCalls("researcher", "later");
                    }
                    maximum.accumulateAndGet(executing.incrementAndGet(), Math::max);
                    try {
                        if (brief(request).equals("later")) {
                            laterChildStarted.set(true);
                            return answer("later finding", 1);
                        }
                        heldChildrenStarted.countDown();
                        awaitIgnoringInterrupts(releaseHeldChildren);
                        return answer("held finding", 1);
                    } finally {
                        executing.decrementAndGet();
                    }
                });
        AgentRunSnapshot hold = fixture.start("hold");
        assertThat(heldChildrenStarted.await(10, TimeUnit.SECONDS)).isTrue();

        fixture.runtime.handle(hold.runId()).cancel(RunCancellation.userRequest());
        AgentRunSnapshot cancelled =
                fixture.runtime.handle(hold.runId()).awaitCompletion(AWAIT).orElseThrow();
        assertThat(cancelled.status()).isEqualTo(AgentRunStatus.CANCELLED);
        // The parent stopped observing, but both child tasks are still inside their model call.
        assertThat(fixture.runtime.children(hold.runId()))
                .hasSize(2)
                .allSatisfy(child -> assertThat(child.status().isTerminal()).isFalse());

        AgentRunSnapshot later = fixture.start("later");
        sleep(1_000);
        assertThat(laterChildStarted).isFalse();
        assertThat(fixture.runtime.children(later.runId())).isEmpty();

        releaseHeldChildren.countDown();
        AgentRunSnapshot completed =
                fixture.runtime.handle(later.runId()).awaitCompletion(AWAIT).orElseThrow();

        assertThat(completed.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(laterChildStarted).isTrue();
        assertThat(maximum.get()).isLessThanOrEqualTo(2);
        List<ChildRunView> heldChildren = fixture.runtime.children(hold.runId());
        assertThat(heldChildren).allSatisfy(child -> {
            AgentRun run = fixture.store.find(child.runId()).orElseThrow();
            assertThat(run.status()).isEqualTo(AgentRunStatus.CANCELLED);
            assertThat(run.terminationReason().orElseThrow().code()).isEqualTo("PARENT_CANCELLED");
        });
    }

    @Test
    void childEndingAfterItsParentStoppedStillProjectsExactlyOneTerminalEventToTheParent() throws Exception {
        CountDownLatch childStarted = new CountDownLatch(1);
        CountDownLatch releaseChild = new CountDownLatch(1);
        Fixture fixture = fixture(Options.defaults(), request -> {
            if (isParent(request)) return taskCalls("researcher", "outlives parent");
            childStarted.countDown();
            awaitIgnoringInterrupts(releaseChild);
            return answer("late finding", 1);
        });
        AgentRunSnapshot started = fixture.start("late-child");
        assertThat(childStarted.await(10, TimeUnit.SECONDS)).isTrue();

        fixture.runtime.handle(started.runId()).cancel(RunCancellation.userRequest());
        AgentRunSnapshot parent =
                fixture.runtime.handle(started.runId()).awaitCompletion(AWAIT).orElseThrow();
        assertThat(parent.status()).isEqualTo(AgentRunStatus.CANCELLED);
        AgentRunId child = fixture.runtime.children(parent.runId()).getFirst().runId();
        assertThat(childTerminalEvents(fixture, parent.runId(), child)).isEmpty();

        releaseChild.countDown();
        eventually(() -> fixture.store.find(child).orElseThrow().status().isTerminal());

        AgentRunStatus childStatus =
                fixture.runtime.children(parent.runId()).getFirst().status();
        assertThat(childStatus).isEqualTo(AgentRunStatus.CANCELLED);
        assertThat(childTerminalEvents(fixture, parent.runId(), child)).containsExactly(terminalEventType(childStatus));
        var lifecycle =
                fixture.runtime.events(parent.runId(), RunEventCursor.beforeFirst(parent.runId()), 500).items().stream()
                        .filter(event -> event.eventType().equals("child.run.cancelled"))
                        .map(event -> (RunEventPayloads.ChildRunLifecycle) event.payload())
                        .findFirst()
                        .orElseThrow();
        assertThat(lifecycle.toolCallId())
                .isEqualTo(
                        delegationCalls(fixture, parent.runId()).getFirst().id().value());
        assertThat(lifecycle.reasonCode()).isEqualTo("PARENT_CANCELLED");
    }

    @Test
    void schedulerRejectionSettlesTheChildInsteadOfLeavingItQueued() throws Exception {
        LocalExecutionScheduler local = new LocalExecutionScheduler();
        closeables.add(local);
        AtomicInteger childSubmissions = new AtomicInteger();
        ExecutionScheduler rejectingFirstChild = new ExecutionScheduler() {
            @Override
            public void submit(AgentRunId runId, Runnable task) {
                if (runId.value().startsWith("child-run-") && childSubmissions.getAndIncrement() == 0) {
                    throw new RejectedExecutionException("scheduler is full");
                }
                local.submit(runId, task);
            }

            @Override
            public void cancel(AgentRunId runId) {
                local.cancel(runId);
            }
        };
        List<AgentChatRequest> parentRequests = new CopyOnWriteArrayList<>();
        Fixture fixture = fixture(
                Options.defaults().customize(builder -> builder.scheduler(rejectingFirstChild)
                        .maxConcurrentChildRuns(1)),
                request -> {
                    if (isParent(request)) {
                        parentRequests.add(request);
                        return hasToolResults(request)
                                ? answer("handled the refusal", 1)
                                : taskCalls("researcher", "refused", "researcher", "accepted");
                    }
                    return answer("accepted finding", 1);
                });

        AgentRunSnapshot parent = fixture.startAndAwait("rejected");

        assertThat(parent.status()).isEqualTo(AgentRunStatus.COMPLETED);
        Map<String, ChildRunView> children = new ConcurrentHashMap<>();
        fixture.runtime.children(parent.runId()).forEach(child -> children.put(child.objective(), child));
        assertThat(children).hasSize(2);
        assertThat(children.values())
                .allSatisfy(child -> assertThat(child.status().isTerminal()).isTrue());
        AgentRunId refused = children.get("refused").runId();
        AgentRun refusedRun = fixture.store.find(refused).orElseThrow();
        assertThat(refusedRun.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(refusedRun.error().orElseThrow().code()).isEqualTo(AgentErrorCode.RUNTIME_EXECUTION_FAILED);
        assertThat(fixture.store.activeFor(refused)).isEmpty();
        assertThat(fixture.store.attemptsFor(refused)).singleElement().satisfies(attempt -> assertThat(attempt.status())
                .isEqualTo(ExecutionAttemptStatus.FAILED));
        // The only process slot came back, so the second child still ran.
        assertThat(children.get("accepted").status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(toolResults(parentRequests.getLast()))
                .anyMatch(text -> text.contains("failed (RUNTIME_EXECUTION_FAILED)"));
        assertThat(childTerminalEvents(fixture, parent.runId(), refused)).containsExactly("child.run.failed:FAILED");
        assertThat(childTerminalEvents(
                        fixture, parent.runId(), children.get("accepted").runId()))
                .containsExactly("child.run.completed:COMPLETED");
    }

    @Test
    void childInheritsTheParentsReferenceInputsButNotItsFreeText() throws Exception {
        URI plan = URI.create("https://images.example.com/floor-plan.png");
        List<AgentChatRequest> childRequests = new CopyOnWriteArrayList<>();
        ResolvedModelSnapshot text = DefaultResolvedModelSnapshots.deepSeekV4Pro();
        EnumSet<ModelCapability> capabilities = EnumSet.copyOf(text.capabilities());
        capabilities.add(ModelCapability.IMAGE_URL_INPUT);
        ResolvedModelSnapshot base = ResolvedModelSnapshot.create(
                text.providerId(),
                text.providerVersion(),
                text.modelId(),
                text.modelVersion(),
                text.providerModelId(),
                text.adapterType(),
                text.adapterVersion(),
                text.apiStyle(),
                text.dialect(),
                text.endpoint(),
                text.credentialRef(),
                text.nativeStreaming(),
                capabilities,
                text.contextWindow(),
                8_192,
                Map.of(),
                Map.of());
        ResolvedModelSnapshot visionModel = base.withEffectiveParameters(new EffectiveModelParameters(
                base.modelId(),
                "2.0",
                "sha256:" + "a".repeat(64),
                ModelReasoningPolicy.disabled(),
                4096,
                Optional.of(ImageInputProfile.standard(Set.of(ModelImageSource.URL), false))));
        Options options = Options.defaults();
        Fixture fixture = fixture(
                options.customize(builder -> builder.profiles((id, overrides) -> new ResolvedProfile(
                        id,
                        "1.0.0",
                        AgentRunType.CHAT,
                        new AgentRunBudget(1_000_000, 1_000_000, 1_000_000, 32, 32, 8, "USD", 1_000_000),
                        id.equals("child-profile") ? options.childLimits() : options.parentLimits(),
                        visionModel))),
                request -> {
                    if (isParent(request)) {
                        return hasToolResults(request)
                                ? answer("done", 1)
                                : taskCalls("researcher", "describe the plan");
                    }
                    childRequests.add(request);
                    return answer("a floor plan", 1);
                });

        AgentRunSnapshot parent = fixture.start(
                "attachments", List.of(new ImageUrlContentPart(plan), new TextPart("private parent note", "plain")));
        parent = fixture.runtime.handle(parent.runId()).awaitCompletion(AWAIT).orElseThrow();

        assertThat(parent.status()).isEqualTo(AgentRunStatus.COMPLETED);
        ModelMessage childUser = childRequests.getFirst().messages().stream()
                .filter(message -> message.role() == ModelMessageRole.USER)
                .findFirst()
                .orElseThrow();
        assertThat(childUser.images()).containsExactly(new ImageUrlPart(plan));
        assertThat(childUser.content()).contains("describe the plan").doesNotContain("private parent note");
        AgentRunId child = fixture.runtime.children(parent.runId()).getFirst().runId();
        assertThat(fixture.store.messages(child).getFirst().contents())
                .filteredOn(part -> !(part instanceof TextPart))
                .containsExactly(new ImageUrlContentPart(plan));
    }

    // ---------------------------------------------------------------------------------------------------------

    @Test
    void insufficientInputWindowRejectsLargeChildToolArgumentsAndResult() throws Exception {
        largeChildToolArgumentsAndTruncatedResultRespectTheInputWindow(32_768);
    }

    @Test
    void sufficientInputWindowAllowsTheNextChildModelTurnAfterLargeArgumentsAndResult() throws Exception {
        largeChildToolArgumentsAndTruncatedResultRespectTheInputWindow(65_536);
    }

    private void largeChildToolArgumentsAndTruncatedResultRespectTheInputWindow(int contextWindow) throws Exception {
        AtomicReference<Throwable> diagnostic = new AtomicReference<>();
        AtomicInteger childCalls = new AtomicInteger();
        String path = "/mnt/user-data/" + "x".repeat(20_000);
        String output = "child completed";
        ResolvedModelSnapshot original = DefaultResolvedModelSnapshots.deepSeekV4Pro();
        ResolvedModelSnapshot bounded = ResolvedModelSnapshot.create(
                original.providerId(),
                original.providerVersion(),
                original.modelId(),
                original.modelVersion(),
                original.providerModelId(),
                original.adapterType(),
                original.adapterVersion(),
                original.apiStyle(),
                original.dialect(),
                original.endpoint(),
                original.credentialRef(),
                true,
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                contextWindow,
                16_384,
                Map.of(),
                Map.of());
        Options options = Options.defaults();
        Fixture fixture = fixture(
                options.tools(
                                (run, binding, request) -> TestToolPlatform.allow(),
                                invocation -> new ToolResult(
                                        true,
                                        "Error: INVALID_PATH\npath=" + path,
                                        Map.of(
                                                "path",
                                                path,
                                                "size",
                                                0,
                                                "sha256",
                                                "",
                                                "encoding",
                                                "",
                                                "totalLines",
                                                0,
                                                "startLine",
                                                0,
                                                "endLine",
                                                0,
                                                "truncated",
                                                false,
                                                "content",
                                                "",
                                                "error",
                                                "INVALID_PATH"),
                                        List.of(),
                                        List.of(),
                                        false))
                        .customize(builder -> builder.failureDiagnostics((context, failure) -> diagnostic.set(failure))
                                .profiles((id, overrides) -> new ResolvedProfile(
                                        id,
                                        "1.0.0",
                                        AgentRunType.CHAT,
                                        AgentRunBudget.disabled(),
                                        id.equals("child-profile") ? options.childLimits() : options.parentLimits(),
                                        bounded))),
                request -> {
                    if (isParent(request))
                        return hasToolResults(request)
                                ? answer("done", 1)
                                : taskCalls("researcher", "Read the bounded file");
                    if (childCalls.incrementAndGet() == 1)
                        return response(List.of(new ModelToolCall(
                                new ProviderToolCallCorrelationId("large-read"), "read_doc", Map.of("path", path))));
                    return answer(output, 1);
                });
        AgentRunSnapshot parent = fixture.startAndAwait("large-tool-result");
        assertThat(parent.status()).isEqualTo(AgentRunStatus.COMPLETED);
        AgentRun child = fixture.store
                .find(fixture.runtime.children(parent.runId()).getFirst().runId())
                .orElseThrow();
        if (contextWindow == 32_768) {
            assertThat(childCalls).hasValue(1);
            assertThat(child.status()).isEqualTo(AgentRunStatus.FAILED);
            assertThat(diagnostic.get())
                    .isInstanceOf(io.haifa.agent.runtime.core.loop.ContextRebuildExhaustedException.class);
        } else {
            assertThat(childCalls).hasValue(2);
            assertThat(child.status()).isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(diagnostic.get()).isNull();
        }
        assertThat(fixture.store.toolCalls(child.id())).singleElement().satisfies(call -> assertThat(
                        call.result().orElseThrow().truncated())
                .isTrue());
    }

    @Test
    void cancellationDuringTerminalCollectionDoesNotAdmitTheWaitingChild() throws Exception {
        terminalCollectionUsesTheCurrentParentStopSignalBeforeAdmission(true);
    }

    @Test
    void terminalCollectionStillAdmitsTheWaitingChildWithoutAStopSignal() throws Exception {
        terminalCollectionUsesTheCurrentParentStopSignalBeforeAdmission(false);
    }

    private void terminalCollectionUsesTheCurrentParentStopSignalBeforeAdmission(boolean cancel) throws Exception {
        var controls = new io.haifa.agent.runtime.core.control.RunControlRegistry();
        var parentAtSecondModel = new CountDownLatch(1);
        var releaseParent = new CountDownLatch(1);
        Fixture fixture = fixture(
                Options.defaults().parallel(1).customize(builder -> builder.controlRegistry(controls)), request -> {
                    if (!isParent(request)) return answer("child", 1);
                    if (!hasToolResults(request)) return taskCalls("researcher", "first");
                    parentAtSecondModel.countDown();
                    awaitIgnoringInterrupts(releaseParent);
                    return answer("done", 1);
                });
        AgentRunSnapshot parent = fixture.start("cancel-during-collect");
        assertThat(parentAtSecondModel.await(AWAIT.toSeconds(), TimeUnit.SECONDS))
                .isTrue();
        assertThat(fixture.store.find(parent.runId()).orElseThrow().status()).isEqualTo(AgentRunStatus.RUNNING);
        ToolCall existing = delegationCalls(fixture, parent.runId()).getFirst();
        List<ToolCallId> notStarted = new ArrayList<>();
        ToolCallId waiting = new ToolCallId("waiting-child-call");
        try {
            Throwable stopped = org.assertj.core.api.Assertions.catchThrowable(() -> fixture.runtime
                    .delegations()
                    .executeChildren(
                            fixture.store.find(parent.runId()).orElseThrow(),
                            List.of(
                                    new DelegationPort.ChildRunRequest(existing.id(), RESEARCHER, "first", "first"),
                                    new DelegationPort.ChildRunRequest(waiting, RESEARCHER, "waiting", "waiting")),
                            new DelegationPort.Listener() {
                                @Override
                                public void terminal(ToolCallId id, AgentRun child) {
                                    if (cancel) controls.requestCancel(parent.runId());
                                }

                                @Override
                                public void rejected(ToolCallId id, String reason) {
                                    throw new AssertionError(reason);
                                }

                                @Override
                                public void notStarted(ToolCallId id) {
                                    notStarted.add(id);
                                }
                            }));
            if (cancel) {
                assertThat(stopped)
                        .isInstanceOf(io.haifa.agent.runtime.core.control.CancellationObservedException.class);
                assertThat(notStarted).containsExactly(waiting);
                assertThat(fixture.runtime.children(parent.runId())).hasSize(1);
                assertThat(fixture.store.find(ChildRunCoordinator.childRunId(parent.runId(), waiting)))
                        .isEmpty();
            } else {
                assertThat(stopped).isNull();
                assertThat(notStarted).isEmpty();
                assertThat(fixture.runtime.children(parent.runId())).hasSize(2);
                assertThat(fixture.store.find(ChildRunCoordinator.childRunId(parent.runId(), waiting)))
                        .get()
                        .satisfies(child -> assertThat(child.status()).isEqualTo(AgentRunStatus.COMPLETED));
            }
        } finally {
            releaseParent.countDown();
        }
        assertThat(fixture.runtime.handle(parent.runId()).awaitCompletion(AWAIT))
                .get()
                .satisfies(result -> assertThat(result.status())
                        .isEqualTo(cancel ? AgentRunStatus.CANCELLED : AgentRunStatus.COMPLETED));
    }

    private Fixture fixture(Options options, Function<AgentChatRequest, AgentChatResponse> script) {
        return fixture(options, script, UnaryOperator.identity());
    }

    private Fixture fixture(
            Options options,
            Function<AgentChatRequest, AgentChatResponse> script,
            UnaryOperator<RuntimeUnitOfWork> unitOfWorkWrapper) {
        LocalExecutionScheduler scheduler = new LocalExecutionScheduler();
        closeables.add(scheduler);
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        RuntimePersistencePorts original = RuntimePersistencePorts.inMemory(store);
        RuntimePersistencePorts persistence = new RuntimePersistencePorts(
                original.sessions(),
                original.runs(),
                original.attempts(),
                original.checkpoints(),
                original.state(),
                original.events(),
                original.outbox(),
                original.idempotency(),
                unitOfWorkWrapper.apply(original.unitOfWork()),
                original.toolJournal(),
                original.interactions(),
                original.runInputs(),
                original.conversationSummaries(),
                original.toolResultAssets(),
                original.messageRedactions());
        AgentChatModel model = script::apply;
        Set<String> parentTools = options.policy == null ? Set.of() : Set.of("read_doc", "write_doc");
        Set<String> researcherTools = options.policy == null ? Set.of() : Set.of("read_doc");
        RuntimeCoreBuilder builder = new RuntimeCoreBuilder()
                .registerChatModel("openai-compatible", "1.0.0", model)
                .scheduler(scheduler)
                .persistence(persistence)
                .modelRetry(RetryPolicy.none())
                .definitions((id, requested) -> {
                    if (id.equals(LEAD)) {
                        return new ResolvedDefinition(
                                LEAD,
                                new AgentDefinitionVersion(1, 0, 0),
                                parentTools,
                                Set.of(),
                                Set.of(RESEARCHER, REVIEWER),
                                "Lead the work.",
                                List.of(),
                                "Lead agent",
                                Optional.empty());
                    }
                    if (id.equals(RESEARCHER)) {
                        return new ResolvedDefinition(
                                RESEARCHER,
                                new AgentDefinitionVersion(1, 0, 0),
                                researcherTools,
                                Set.of(),
                                Set.of(),
                                "Research read-only.",
                                List.of(),
                                "Reads documents and reports findings",
                                Optional.of("child-profile"));
                    }
                    if (id.equals(REVIEWER)) {
                        return new ResolvedDefinition(
                                REVIEWER,
                                new AgentDefinitionVersion(1, 0, 0),
                                parentTools,
                                Set.of(),
                                Set.of(),
                                "Review and edit.",
                                List.of(),
                                "Reviews and edits documents",
                                Optional.empty());
                    }
                    throw new IllegalArgumentException("unknown definition");
                })
                .profiles((id, overrides) -> new ResolvedProfile(
                        id,
                        "1.0.0",
                        AgentRunType.CHAT,
                        new AgentRunBudget(1_000_000, 1_000_000, 1_000_000, 32, 32, 8, "USD", 1_000_000),
                        id.equals("child-profile") ? options.childLimits() : options.parentLimits()));
        if (options.policy != null) {
            TestToolPlatform.installReadWrite(builder, "read_doc", "write_doc", options.policy, options.tools::invoke);
        }
        builder = options.customizer.apply(builder);
        return new Fixture(builder.build(), store);
    }

    private static boolean isParent(AgentChatRequest request) {
        return toolNames(request).contains(DelegationTool.NAME);
    }

    private static List<String> toolNames(AgentChatRequest request) {
        return request.tools().stream().map(ModelToolSpecification::name).toList();
    }

    private static boolean hasToolResults(AgentChatRequest request) {
        return request.messages().stream().anyMatch(message -> message.role() == ModelMessageRole.TOOL);
    }

    private static List<String> toolResults(AgentChatRequest request) {
        return request.messages().stream()
                .filter(message -> message.role() == ModelMessageRole.TOOL)
                .map(ModelMessage::content)
                .toList();
    }

    private static String brief(AgentChatRequest request) {
        return request.messages().stream()
                .filter(message -> message.role() == ModelMessageRole.USER)
                .map(ModelMessage::content)
                .findFirst()
                .orElseThrow();
    }

    private static List<ToolCall> delegationCalls(Fixture fixture, AgentRunId parent) {
        return fixture.store.toolCalls(parent).stream()
                .filter(DelegationTool::isDelegation)
                .toList();
    }

    private static AgentChatResponse taskCalls(String... agentObjectivePairs) {
        List<ModelToolCall> calls = new ArrayList<>();
        for (int index = 0; index < agentObjectivePairs.length; index += 2) {
            calls.add(new ModelToolCall(
                    new ProviderToolCallCorrelationId("task-" + index + "-" + agentObjectivePairs[index + 1]),
                    DelegationTool.NAME,
                    Map.of("agent", agentObjectivePairs[index], "objective", agentObjectivePairs[index + 1])));
        }
        return response(calls);
    }

    private static AgentChatResponse toolCall(String name) {
        return response(List.of(new ModelToolCall(new ProviderToolCallCorrelationId("call-" + name), name, Map.of())));
    }

    private static AgentChatResponse response(List<ModelToolCall> calls) {
        return new AgentChatResponse(
                "response", "stub", "", calls, ModelFinishReason.TOOL_CALLS, ModelUsage.unpriced(1, 1), "", Map.of());
    }

    private static AgentChatResponse answer(String text, long inputTokens) {
        return new AgentChatResponse(
                "response",
                "stub",
                text,
                List.of(),
                ModelFinishReason.STOP,
                ModelUsage.unpriced(inputTokens, 1),
                "",
                Map.of());
    }

    private static boolean await(CountDownLatch latch, long seconds) {
        try {
            return latch.await(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            throw new IllegalStateException("stub model interrupted", interrupted);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            throw new IllegalStateException("stub model interrupted", interrupted);
        }
    }

    /** Waits like a model call that ignores interruption, so the child task outlives the parent's stop. */
    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        long deadline = System.nanoTime() + AWAIT.toNanos();
        while (latch.getCount() > 0 && System.nanoTime() < deadline) {
            try {
                latch.await(50, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
                // Deliberately keeps running after the parent's cancellation interrupts this child.
            }
        }
    }

    private static List<String> childTerminalEvents(Fixture fixture, AgentRunId parent, AgentRunId child) {
        return fixture.runtime.events(parent, RunEventCursor.beforeFirst(parent), 500).items().stream()
                .filter(event -> event.eventType().startsWith("child.run.")
                        && !event.eventType().equals("child.run.started"))
                .filter(event -> ((RunEventPayloads.ChildRunLifecycle) event.payload())
                        .childRunId()
                        .equals(child.value()))
                .map(event -> event.eventType() + ":" + ((RunEventPayloads.ChildRunLifecycle) event.payload()).status())
                .toList();
    }

    private static String terminalEventType(AgentRunStatus status) {
        return switch (status) {
            case COMPLETED -> "child.run.completed:COMPLETED";
            case FAILED -> "child.run.failed:FAILED";
            case CANCELLED -> "child.run.cancelled:CANCELLED";
            case TIMEOUT -> "child.run.timed-out:TIMEOUT";
            default -> throw new AssertionError("not terminal: " + status);
        };
    }

    private static void eventually(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + AWAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("condition was not reached in time");
            Thread.sleep(20);
        }
    }

    private record Fixture(DefaultAgentRuntime runtime, InMemoryRuntimeStore store) {
        AgentRunSnapshot start(String key) {
            return start(key, List.of());
        }

        AgentRunSnapshot start(String key, List<ContentPart> inputs) {
            return runtime.start(new AgentRunRequest(
                    key,
                    LEAD,
                    Optional.empty(),
                    "parent-profile",
                    new AgentSessionId("session-" + key),
                    Optional.empty(),
                    "objective " + key,
                    inputs,
                    RuntimeOverrides.NONE));
        }

        AgentRunSnapshot startAndAwait(String key) throws InterruptedException {
            AgentRunSnapshot started = start(key);
            return runtime.handle(started.runId()).awaitCompletion(AWAIT).orElseThrow();
        }
    }

    private record Options(
            int parallel,
            long parentWall,
            long parentIdle,
            long childWall,
            long maxChildRuns,
            PublicToolPolicy policy,
            TestToolPlatform.ToolHandler tools,
            UnaryOperator<RuntimeCoreBuilder> customizer) {
        static Options defaults() {
            return new Options(4, 20_000, 20_000, 20_000, 8, null, null, UnaryOperator.identity());
        }

        Options parallel(int value) {
            return new Options(value, parentWall, parentIdle, childWall, maxChildRuns, policy, tools, customizer);
        }

        Options parentWall(long value) {
            return new Options(parallel, value, parentIdle, childWall, maxChildRuns, policy, tools, customizer);
        }

        Options parentIdle(long value) {
            return new Options(parallel, parentWall, value, childWall, maxChildRuns, policy, tools, customizer);
        }

        Options childWall(long value) {
            return new Options(parallel, parentWall, parentIdle, value, maxChildRuns, policy, tools, customizer);
        }

        Options maxChildRuns(long value) {
            return new Options(parallel, parentWall, parentIdle, childWall, value, policy, tools, customizer);
        }

        Options tools(PublicToolPolicy value, TestToolPlatform.ToolHandler handler) {
            return new Options(parallel, parentWall, parentIdle, childWall, maxChildRuns, value, handler, customizer);
        }

        Options customize(UnaryOperator<RuntimeCoreBuilder> value) {
            return new Options(parallel, parentWall, parentIdle, childWall, maxChildRuns, policy, tools, value);
        }

        AgentRunLimits parentLimits() {
            return new AgentRunLimits(10, 1, parallel, parentWall, parentIdle, 20, 20, maxChildRuns);
        }

        AgentRunLimits childLimits() {
            return new AgentRunLimits(10, 1, 1, childWall, 20_000, 20, 20, 0);
        }
    }
}
