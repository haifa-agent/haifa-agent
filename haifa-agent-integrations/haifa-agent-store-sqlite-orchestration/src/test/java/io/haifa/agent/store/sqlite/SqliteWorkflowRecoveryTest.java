package io.haifa.agent.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.orchestration.api.CompiledWorkflowDefinition;
import io.haifa.agent.orchestration.api.WorkflowCapability;
import io.haifa.agent.orchestration.api.WorkflowDefinition;
import io.haifa.agent.orchestration.api.WorkflowDefinitionId;
import io.haifa.agent.orchestration.api.WorkflowDefinitionVersion;
import io.haifa.agent.orchestration.api.WorkflowEdge;
import io.haifa.agent.orchestration.api.WorkflowErrorCode;
import io.haifa.agent.orchestration.api.WorkflowEventType;
import io.haifa.agent.orchestration.api.WorkflowException;
import io.haifa.agent.orchestration.api.WorkflowLimits;
import io.haifa.agent.orchestration.api.WorkflowNodeAttemptStatus;
import io.haifa.agent.orchestration.api.WorkflowNodeDefinition;
import io.haifa.agent.orchestration.api.WorkflowNodeId;
import io.haifa.agent.orchestration.api.WorkflowNodeType;
import io.haifa.agent.orchestration.api.WorkflowResumeRequest;
import io.haifa.agent.orchestration.api.WorkflowRunSnapshot;
import io.haifa.agent.orchestration.api.WorkflowSignalId;
import io.haifa.agent.orchestration.api.WorkflowStartRequest;
import io.haifa.agent.orchestration.api.WorkflowState;
import io.haifa.agent.orchestration.api.WorkflowStateDelta;
import io.haifa.agent.orchestration.api.WorkflowStateMapping;
import io.haifa.agent.orchestration.api.WorkflowStateSchema;
import io.haifa.agent.orchestration.api.WorkflowStatus;
import io.haifa.agent.orchestration.api.WorkflowSubgraphBinding;
import io.haifa.agent.orchestration.api.WorkflowTimeoutRequest;
import io.haifa.agent.orchestration.core.DefaultWorkflowDefinitionCompiler;
import io.haifa.agent.orchestration.core.DurableWorkflowRuntime;
import io.haifa.agent.orchestration.core.spi.DurableWorkflowAgentGateway;
import io.haifa.agent.orchestration.core.spi.WorkflowActionGateway;
import io.haifa.agent.orchestration.core.spi.WorkflowAgentGateway;
import io.haifa.agent.orchestration.core.spi.WorkflowConditionEvaluator;
import io.haifa.agent.orchestration.core.spi.WorkflowFailureInjector;
import io.haifa.agent.orchestration.core.spi.WorkflowFailurePoint;
import io.haifa.agent.orchestration.core.spi.WorkflowPersistenceBinding;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteWorkflowRecoveryTest {
    private static final WorkflowStateSchema SCHEMA =
            new WorkflowStateSchema("durable-fixture", 1, Set.of("count", "choice", "left", "right"), 32, 4, 256);
    private static final WorkflowPersistenceBinding BINDING =
            new WorkflowPersistenceBinding("haifa:orchestration-core", "1", "0".repeat(64), 1);
    private static final WorkflowAgentGateway NO_AGENT = (runId, node, state) ->
            new WorkflowAgentGateway.AgentExecution(new AgentRunId("unused"), WorkflowStateDelta.empty());
    private static final TimeProvider TIME = () -> Instant.parse("2026-08-19T03:00:00.123456Z");

    @TempDir
    Path directory;

    @Test
    void resumesWaitingRunAcrossProcessRestartAndKeepsCommandsAndOutboxIdempotent() {
        CompiledWorkflowDefinition definition = waitDefinition();
        WorkflowRunSnapshot waiting;
        try (SqliteWorkflowStoreFoundation first = SqliteWorkflowStoreTestSupport.foundation(directory)) {
            waiting = runtime(first, definition, noAction(), WorkflowFailureInjector.NONE, "first")
                    .start(start(definition, Map.of(), "start-wait"));
            assertThat(waiting.status()).isEqualTo(WorkflowStatus.WAITING);
            assertThat(first.workflows().recoverable()).singleElement();
        }

        try (SqliteWorkflowStoreFoundation second = SqliteWorkflowStoreTestSupport.foundation(directory)) {
            DurableWorkflowRuntime runtime =
                    runtime(second, definition, noAction(), WorkflowFailureInjector.NONE, "second");
            WorkflowResumeRequest request = new WorkflowResumeRequest(
                    waiting.id(),
                    waiting.activeWait().orElseThrow().id(),
                    waiting.revision(),
                    new WorkflowSignalId("signal-1"),
                    "resume-1",
                    new WorkflowStateDelta(Map.of("choice", true)));

            WorkflowRunSnapshot completed = runtime.resume(request);

            assertThat(completed.status()).isEqualTo(WorkflowStatus.COMPLETED);
            assertThat(runtime.resume(request)).isEqualTo(completed);
            assertThat(runtime.events(completed.id(), 0, 100))
                    .extracting(event -> event.type())
                    .contains(WorkflowEventType.WAITING, WorkflowEventType.RESUMED, WorkflowEventType.COMPLETED);
            assertThat(second.workflows().pendingOutbox(100)).hasSameSizeAs(runtime.events(completed.id(), 0, 100));
            var firstOutbox = second.workflows().pendingOutbox(1).getFirst();
            second.workflows()
                    .markOutboxPublished(
                            firstOutbox.event().runId(), firstOutbox.event().sequence(), TIME.now());
            second.workflows()
                    .markOutboxPublished(
                            firstOutbox.event().runId(), firstOutbox.event().sequence(), TIME.now());
            assertThat(second.workflows().pendingOutbox(100))
                    .noneMatch(record ->
                            record.event().sequence() == firstOutbox.event().sequence());
        }
    }

    @Test
    void classifiesCommittedAttemptWithoutResultAsOutcomeUnknownWithoutReplay() {
        CompiledWorkflowDefinition definition = actionDefinition();
        AtomicInteger calls = new AtomicInteger();
        WorkflowActionGateway action = (runId, node, state) -> {
            calls.incrementAndGet();
            return new WorkflowStateDelta(Map.of("count", 1));
        };
        WorkflowRunSnapshot crashed;
        try (SqliteWorkflowStoreFoundation first = SqliteWorkflowStoreTestSupport.foundation(directory)) {
            DurableWorkflowRuntime runtime =
                    runtime(first, definition, action, once(WorkflowFailurePoint.AFTER_ATTEMPT_SCHEDULED), "first");
            assertThatThrownBy(() -> runtime.start(start(definition, Map.of(), "start-crash-before-dispatch")))
                    .isInstanceOf(SimulatedCrash.class);
            crashed = first.workflows().recoverable().getFirst().snapshot();
            assertThat(crashed.attempts())
                    .singleElement()
                    .extracting(attempt -> attempt.status())
                    .isEqualTo(WorkflowNodeAttemptStatus.RUNNING);
            assertThat(calls).hasValue(0);
        }

        try (SqliteWorkflowStoreFoundation second = SqliteWorkflowStoreTestSupport.foundation(directory)) {
            WorkflowRunSnapshot recovered = runtime(second, definition, action, WorkflowFailureInjector.NONE, "second")
                    .recover(crashed.id());
            assertThat(recovered.status()).isEqualTo(WorkflowStatus.FAILED);
            assertThat(recovered.failure().orElseThrow().code()).isEqualTo(WorkflowErrorCode.OUTCOME_UNKNOWN);
            assertThat(recovered.attempts().getFirst().status()).isEqualTo(WorkflowNodeAttemptStatus.OUTCOME_UNKNOWN);
            assertThat(calls).hasValue(0);
        }
    }

    @Test
    void continuesAfterCrashBetweenRunCreationAndFirstNodeSchedule() {
        CompiledWorkflowDefinition definition = actionDefinition();
        AtomicInteger calls = new AtomicInteger();
        WorkflowActionGateway action = (runId, node, state) -> {
            calls.incrementAndGet();
            return new WorkflowStateDelta(Map.of("count", 1));
        };
        WorkflowRunSnapshot crashed;
        try (SqliteWorkflowStoreFoundation first = SqliteWorkflowStoreTestSupport.foundation(directory)) {
            assertThatThrownBy(() -> runtime(
                                    first, definition, action, once(WorkflowFailurePoint.AFTER_RUN_CREATED), "first")
                            .start(start(definition, Map.of(), "start-before-schedule")))
                    .isInstanceOf(SimulatedCrash.class);
            crashed = first.workflows().recoverable().getFirst().snapshot();
            assertThat(crashed.attempts()).isEmpty();
        }
        try (SqliteWorkflowStoreFoundation second = SqliteWorkflowStoreTestSupport.foundation(directory)) {
            WorkflowRunSnapshot recovered = runtime(second, definition, action, WorkflowFailureInjector.NONE, "second")
                    .recover(crashed.id());
            assertThat(recovered.status()).isEqualTo(WorkflowStatus.COMPLETED);
            assertThat(calls).hasValue(1);
            assertThat(runtime(second, definition, action, WorkflowFailureInjector.NONE, "third")
                            .start(start(definition, Map.of(), "start-before-schedule")))
                    .isEqualTo(recovered);
        }
    }

    @Test
    void appliesCommittedNodeResultAfterRestartWithoutReexecutingSideEffect() {
        CompiledWorkflowDefinition definition = actionDefinition();
        AtomicInteger calls = new AtomicInteger();
        WorkflowActionGateway action = (runId, node, state) -> {
            calls.incrementAndGet();
            return new WorkflowStateDelta(Map.of("count", 7L));
        };
        WorkflowRunSnapshot crashed;
        try (SqliteWorkflowStoreFoundation first = SqliteWorkflowStoreTestSupport.foundation(directory)) {
            DurableWorkflowRuntime runtime =
                    runtime(first, definition, action, once(WorkflowFailurePoint.AFTER_NODE_RESULT_STORED), "first");
            assertThatThrownBy(() -> runtime.start(start(definition, Map.of(), "start-crash-after-result")))
                    .isInstanceOf(SimulatedCrash.class);
            crashed = first.workflows().recoverable().getFirst().snapshot();
            assertThat(calls).hasValue(1);
        }

        try (SqliteWorkflowStoreFoundation second = SqliteWorkflowStoreTestSupport.foundation(directory)) {
            WorkflowRunSnapshot recovered = runtime(second, definition, action, WorkflowFailureInjector.NONE, "second")
                    .recover(crashed.id());
            assertThat(recovered.status()).isEqualTo(WorkflowStatus.COMPLETED);
            assertThat(recovered.state().values()).containsEntry("count", 7L);
            assertThat(calls).hasValue(1);
        }
    }

    @Test
    void atomicallyCommitsAgentRunCreationAndAttemptAssociationThenRecoversTerminalResult() {
        CompiledWorkflowDefinition definition = agentDefinition();
        WorkflowRunSnapshot crashed;
        try (SqliteWorkflowStoreFoundation first = SqliteWorkflowStoreTestSupport.foundation(directory)) {
            DurableWorkflowAgentGateway gateway = agentGateway(first, true);
            assertThatThrownBy(() -> runtime(
                                    first,
                                    definition,
                                    noAction(),
                                    gateway,
                                    once(WorkflowFailurePoint.AFTER_AGENT_RUN_ASSOCIATED),
                                    "first",
                                    BINDING)
                            .start(start(definition, Map.of(), "agent-associated")))
                    .isInstanceOf(SimulatedCrash.class);
            crashed = first.workflows().recoverable().getFirst().snapshot();
            assertThat(crashed.attempts().getFirst().agentRunId()).contains(new AgentRunId("run"));
            assertThat(first.runtime().runs().find(new AgentRunId("run"))).isPresent();
        }
        try (SqliteWorkflowStoreFoundation second = SqliteWorkflowStoreTestSupport.foundation(directory)) {
            WorkflowRunSnapshot recovered = runtime(
                            second,
                            definition,
                            noAction(),
                            agentGateway(second, true),
                            WorkflowFailureInjector.NONE,
                            "second",
                            BINDING)
                    .recover(crashed.id());
            assertThat(recovered.status()).isEqualTo(WorkflowStatus.COMPLETED);
            assertThat(recovered.state().values()).containsEntry("count", 9);
        }
    }

    @Test
    void rollsBackAgentRunCreationWhenAttemptAssociationCannotCommit() throws Exception {
        CompiledWorkflowDefinition definition = agentDefinition();
        try (SqliteWorkflowStoreFoundation foundation = SqliteWorkflowStoreTestSupport.foundation(directory)) {
            try (Connection connection = foundation.runtime().connections().openConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute(
                        "CREATE TRIGGER fail_workflow_agent_association BEFORE INSERT ON workflow_node_attempt "
                                + "WHEN NEW.agent_run_id IS NOT NULL BEGIN SELECT RAISE(ABORT, 'injected'); END");
            }
            DurableWorkflowRuntime runtime = runtime(
                    foundation,
                    definition,
                    noAction(),
                    agentGateway(foundation, true),
                    WorkflowFailureInjector.NONE,
                    "atomic",
                    BINDING);

            assertThatThrownBy(() -> runtime.start(start(definition, Map.of(), "agent-rollback")))
                    .isInstanceOf(SqliteStoreException.class);

            assertThat(foundation.runtime().runs().find(new AgentRunId("run"))).isEmpty();
            WorkflowRunSnapshot unresolved =
                    foundation.workflows().recoverable().getFirst().snapshot();
            assertThat(unresolved.attempts().getFirst().agentRunId()).isEmpty();
            try (Connection connection = foundation.runtime().connections().openConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute("DROP TRIGGER fail_workflow_agent_association");
            }
            assertThat(runtime(
                                    foundation,
                                    definition,
                                    noAction(),
                                    agentGateway(foundation, false),
                                    WorkflowFailureInjector.NONE,
                                    "recover",
                                    BINDING)
                            .recover(unresolved.id())
                            .failure()
                            .orElseThrow()
                            .code())
                    .isEqualTo(WorkflowErrorCode.OUTCOME_UNKNOWN);
        }
    }

    @Test
    void persistsFixedAllOfCursorAndContinuesRemainingBranchAfterRestart() {
        CompiledWorkflowDefinition definition = forkDefinition();
        AtomicInteger leftCalls = new AtomicInteger();
        AtomicInteger rightCalls = new AtomicInteger();
        WorkflowActionGateway action = (runId, node, state) -> {
            if (node.id().value().equals("left")) {
                leftCalls.incrementAndGet();
                return new WorkflowStateDelta(Map.of("left", "L"));
            }
            rightCalls.incrementAndGet();
            return new WorkflowStateDelta(Map.of("right", "R"));
        };
        AtomicInteger storedResults = new AtomicInteger();
        WorkflowFailureInjector failSecondResult = point -> {
            if (point == WorkflowFailurePoint.AFTER_NODE_RESULT_STORED && storedResults.incrementAndGet() == 2) {
                throw new SimulatedCrash();
            }
        };
        WorkflowRunSnapshot crashed;
        try (SqliteWorkflowStoreFoundation first = SqliteWorkflowStoreTestSupport.foundation(directory)) {
            assertThatThrownBy(() -> runtime(first, definition, action, failSecondResult, "first")
                            .start(start(definition, Map.of(), "start-fork")))
                    .isInstanceOf(SimulatedCrash.class);
            crashed = first.workflows().recoverable().getFirst().snapshot();
        }
        try (SqliteWorkflowStoreFoundation second = SqliteWorkflowStoreTestSupport.foundation(directory)) {
            WorkflowRunSnapshot recovered = runtime(second, definition, action, WorkflowFailureInjector.NONE, "second")
                    .recover(crashed.id());
            assertThat(recovered.status()).isEqualTo(WorkflowStatus.COMPLETED);
            assertThat(recovered.state().values()).containsEntry("left", "L").containsEntry("right", "R");
            assertThat(leftCalls).hasValue(1);
            assertThat(rightCalls).hasValue(1);
        }
    }

    @Test
    void persistsSelectedDynamicBranchesAndContinuesAfterRestart() {
        CompiledWorkflowDefinition definition = dynamicForkDefinition();
        AtomicInteger calls = new AtomicInteger();
        WorkflowActionGateway action = (runId, node, state) -> {
            calls.incrementAndGet();
            return new WorkflowStateDelta(
                    node.id().value().equals("left") ? Map.of("left", "L") : Map.of("right", "R"));
        };
        WorkflowRunSnapshot crashed;
        try (SqliteWorkflowStoreFoundation first = SqliteWorkflowStoreTestSupport.foundation(directory)) {
            assertThatThrownBy(() -> runtime(
                                    first,
                                    definition,
                                    action,
                                    NO_AGENT,
                                    (condition, state) -> true,
                                    once(WorkflowFailurePoint.AFTER_NODE_RESULT_STORED),
                                    "dynamic-first",
                                    BINDING)
                            .start(start(definition, Map.of(), "dynamic-start")))
                    .isInstanceOf(SimulatedCrash.class);
            crashed = first.workflows().recoverable().getFirst().snapshot();
        }
        try (SqliteWorkflowStoreFoundation second = SqliteWorkflowStoreTestSupport.foundation(directory)) {
            WorkflowRunSnapshot result = runtime(
                            second,
                            definition,
                            action,
                            NO_AGENT,
                            (condition, state) -> {
                                throw new AssertionError("persisted selection must not be evaluated again");
                            },
                            WorkflowFailureInjector.NONE,
                            "dynamic-second",
                            BINDING)
                    .recover(crashed.id());
            assertThat(result.status()).isEqualTo(WorkflowStatus.COMPLETED);
            assertThat(result.state().values()).containsEntry("left", "L").containsEntry("right", "R");
            assertThat(calls).hasValue(2);
        }
    }

    @Test
    void persistsAnyOfWinnerSelectionAndDoesNotReplayKnownFailureAfterRestart() {
        CompiledWorkflowDefinition definition = anyOfDefinition();
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger secondCalls = new AtomicInteger();
        AtomicInteger thirdCalls = new AtomicInteger();
        WorkflowActionGateway action = (runId, node, state) -> {
            if (node.id().value().equals("first")) {
                firstCalls.incrementAndGet();
                throw new WorkflowException(WorkflowErrorCode.NODE_EXECUTION_FAILED, "fixture", "known failure");
            }
            if (node.id().value().equals("second")) {
                secondCalls.incrementAndGet();
                return new WorkflowStateDelta(Map.of("choice", "second"));
            }
            thirdCalls.incrementAndGet();
            return new WorkflowStateDelta(Map.of("choice", "third"));
        };
        WorkflowRunSnapshot crashed;
        try (SqliteWorkflowStoreFoundation first = SqliteWorkflowStoreTestSupport.foundation(directory)) {
            assertThatThrownBy(() -> runtime(
                                    first,
                                    definition,
                                    action,
                                    once(WorkflowFailurePoint.AFTER_NODE_RESULT_STORED),
                                    "any-first")
                            .start(start(definition, Map.of(), "any-start")))
                    .isInstanceOf(SimulatedCrash.class);
            crashed = first.workflows().recoverable().getFirst().snapshot();
        }
        try (SqliteWorkflowStoreFoundation second = SqliteWorkflowStoreTestSupport.foundation(directory)) {
            DurableWorkflowRuntime recoveredRuntime =
                    runtime(second, definition, action, WorkflowFailureInjector.NONE, "any-second");
            WorkflowRunSnapshot result = recoveredRuntime.recover(crashed.id());
            assertThat(result.status()).isEqualTo(WorkflowStatus.COMPLETED);
            assertThat(result.state().values()).containsEntry("choice", "second");
            assertThat(firstCalls).hasValue(1);
            assertThat(secondCalls).hasValue(1);
            assertThat(thirdCalls).hasValue(0);
            assertThat(recoveredRuntime.events(result.id(), 0, 100))
                    .extracting(event -> event.type())
                    .contains(WorkflowEventType.ANY_OF_WINNER_SELECTED, WorkflowEventType.ANY_OF_LOSER_CANCELLED);
        }
    }

    @Test
    void persistsAndMergesStaticSubgraphsInsideFixedBranches() {
        CompiledWorkflowDefinition definition = subgraphForkDefinition();
        WorkflowActionGateway increment = (runId, node, state) ->
                new WorkflowStateDelta(Map.of("count", ((Integer) state.values().get("count")) + 1));
        try (SqliteWorkflowStoreFoundation foundation = SqliteWorkflowStoreTestSupport.foundation(directory)) {
            WorkflowRunSnapshot result = runtime(
                            foundation, definition, increment, WorkflowFailureInjector.NONE, "parallel-subgraph")
                    .start(start(definition, Map.of("left", 1, "right", 5), "parallel-subgraphs"));

            assertThat(result.status()).isEqualTo(WorkflowStatus.COMPLETED);
            assertThat(result.state().values()).containsEntry("left", 2).containsEntry("right", 6);
            assertThat(result.attempts())
                    .extracting(attempt -> attempt.nodeId().value())
                    .containsExactly("left-sub", "right-sub");
        }
    }

    @Test
    void preservesCheckpointAndConsumesResumeExactlyOnceAcrossCrashes() {
        CompiledWorkflowDefinition definition = waitDefinition();
        WorkflowRunSnapshot waiting;
        try (SqliteWorkflowStoreFoundation first = SqliteWorkflowStoreTestSupport.foundation(directory)) {
            assertThatThrownBy(() -> runtime(
                                    first,
                                    definition,
                                    noAction(),
                                    once(WorkflowFailurePoint.AFTER_CHECKPOINT_STORED),
                                    "first")
                            .start(start(definition, Map.of(), "checkpoint-crash")))
                    .isInstanceOf(SimulatedCrash.class);
            waiting = first.workflows().recoverable().getFirst().snapshot();
            assertThat(waiting.status()).isEqualTo(WorkflowStatus.WAITING);
            assertThat(waiting.checkpoint()).isPresent();
        }
        WorkflowResumeRequest resume = new WorkflowResumeRequest(
                waiting.id(),
                waiting.activeWait().orElseThrow().id(),
                waiting.revision(),
                new WorkflowSignalId("resume-crash-signal"),
                "resume-crash-command",
                new WorkflowStateDelta(Map.of("choice", true)));
        try (SqliteWorkflowStoreFoundation second = SqliteWorkflowStoreTestSupport.foundation(directory)) {
            assertThatThrownBy(() -> runtime(
                                    second,
                                    definition,
                                    noAction(),
                                    once(WorkflowFailurePoint.AFTER_RESUME_CONSUMED),
                                    "second")
                            .resume(resume))
                    .isInstanceOf(SimulatedCrash.class);
            assertThat(second.workflows()
                            .find(waiting.id())
                            .orElseThrow()
                            .snapshot()
                            .status())
                    .isEqualTo(WorkflowStatus.RUNNING);
        }
        try (SqliteWorkflowStoreFoundation third = SqliteWorkflowStoreTestSupport.foundation(directory)) {
            DurableWorkflowRuntime runtime =
                    runtime(third, definition, noAction(), WorkflowFailureInjector.NONE, "third");
            WorkflowRunSnapshot completed = runtime.recover(waiting.id());
            assertThat(completed.status()).isEqualTo(WorkflowStatus.COMPLETED);
            assertThat(runtime.resume(resume)).isEqualTo(completed);
            WorkflowResumeRequest duplicateSignal = new WorkflowResumeRequest(
                    resume.runId(),
                    resume.waitId(),
                    resume.expectedRevision(),
                    resume.signalId(),
                    "different-command",
                    resume.delta());
            assertThatThrownBy(() -> runtime.resume(duplicateSignal))
                    .isInstanceOfSatisfying(WorkflowException.class, error -> assertThat(error.code())
                            .isEqualTo(WorkflowErrorCode.INVALID_RESUME));
        }
    }

    @Test
    void resolvesCancelAndNodeCompletionRaceWithOneAuthoritativeRevision() throws Exception {
        CompiledWorkflowDefinition definition = actionDefinition();
        CountDownLatch executing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        WorkflowActionGateway blocking = (runId, node, state) -> {
            executing.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test action timed out");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return new WorkflowStateDelta(Map.of("count", 1));
        };
        try (SqliteWorkflowStoreFoundation foundation = SqliteWorkflowStoreTestSupport.foundation(directory)) {
            DurableWorkflowRuntime starter =
                    runtime(foundation, definition, blocking, WorkflowFailureInjector.NONE, "starter");
            AtomicReference<Throwable> startFailure = new AtomicReference<>();
            Thread startThread = Thread.ofVirtual().start(() -> {
                try {
                    starter.start(start(definition, Map.of(), "cancel-race"));
                } catch (Throwable failure) {
                    startFailure.set(failure);
                }
            });
            assertThat(executing.await(5, TimeUnit.SECONDS)).isTrue();
            WorkflowRunSnapshot running =
                    foundation.workflows().recoverable().getFirst().snapshot();
            WorkflowRunSnapshot cancelled = runtime(
                            foundation, definition, blocking, WorkflowFailureInjector.NONE, "canceller")
                    .cancel(new io.haifa.agent.orchestration.api.WorkflowCancelRequest(running.id(), "cancel-1"));
            release.countDown();
            startThread.join();

            assertThat(cancelled.status()).isEqualTo(WorkflowStatus.CANCELLED);
            assertThat(startFailure.get()).isInstanceOf(SqliteStoreException.class);
            assertThat(startFailure.get().getCause())
                    .isInstanceOfSatisfying(WorkflowException.class, error -> assertThat(error.code())
                            .isEqualTo(WorkflowErrorCode.PERSISTENCE_CONFLICT));
            assertThat(foundation
                            .workflows()
                            .find(running.id())
                            .orElseThrow()
                            .snapshot()
                            .status())
                    .isEqualTo(WorkflowStatus.CANCELLED);
        }
    }

    @Test
    void rollsBackOutboxProjectionMutationWithTheSharedUnitOfWork() {
        CompiledWorkflowDefinition definition = actionDefinition();
        try (SqliteWorkflowStoreFoundation foundation = SqliteWorkflowStoreTestSupport.foundation(directory)) {
            runtime(foundation, definition, noAction(), WorkflowFailureInjector.NONE, "outbox")
                    .start(start(definition, Map.of(), "outbox-rollback"));
            var pending = foundation.workflows().pendingOutbox(1).getFirst();

            assertThatThrownBy(() -> foundation.unitOfWork().execute(() -> {
                        foundation
                                .workflows()
                                .markOutboxPublished(
                                        pending.event().runId(), pending.event().sequence(), TIME.now());
                        throw new SimulatedCrash();
                    }))
                    .isInstanceOf(SqliteStoreException.class)
                    .hasRootCauseInstanceOf(SimulatedCrash.class);

            assertThat(foundation.workflows().pendingOutbox(100))
                    .anyMatch(record ->
                            record.event().runId().equals(pending.event().runId())
                                    && record.event().sequence()
                                            == pending.event().sequence());
        }
    }

    @Test
    void failsClosedWhenFrozenAdapterBindingOrCodecDoesNotMatch() {
        CompiledWorkflowDefinition definition = waitDefinition();
        WorkflowRunSnapshot waiting;
        try (SqliteWorkflowStoreFoundation first = SqliteWorkflowStoreTestSupport.foundation(directory)) {
            waiting = runtime(first, definition, noAction(), WorkflowFailureInjector.NONE, "first")
                    .start(start(definition, Map.of(), "binding"));
        }
        try (SqliteWorkflowStoreFoundation second = SqliteWorkflowStoreTestSupport.foundation(directory)) {
            WorkflowPersistenceBinding drift = new WorkflowPersistenceBinding("other-adapter", "1", "0".repeat(64), 1);
            DurableWorkflowRuntime runtime =
                    runtime(second, definition, noAction(), WorkflowFailureInjector.NONE, "second", drift);
            assertThatThrownBy(() -> runtime.recover(waiting.id()))
                    .isInstanceOfSatisfying(WorkflowException.class, error -> assertThat(error.code())
                            .isEqualTo(WorkflowErrorCode.BINDING_MISMATCH));
        }
    }

    @Test
    void failsClosedForCodecDefinitionAndPayloadIntegrityDrift() throws Exception {
        CompiledWorkflowDefinition definition = waitDefinition();
        WorkflowRunSnapshot waiting;
        try (SqliteWorkflowStoreFoundation first = SqliteWorkflowStoreTestSupport.foundation(directory)) {
            waiting = runtime(first, definition, noAction(), WorkflowFailureInjector.NONE, "first")
                    .start(start(definition, Map.of(), "drift"));
            try (Connection connection = first.runtime().connections().openConnection();
                    Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE workflow_run SET state_codec_version=2 WHERE workflow_run_id='"
                        + waiting.id().value() + "'");
            }
        }
        try (SqliteWorkflowStoreFoundation second = SqliteWorkflowStoreTestSupport.foundation(directory)) {
            assertThatThrownBy(() -> runtime(second, definition, noAction(), WorkflowFailureInjector.NONE, "second")
                            .recover(waiting.id()))
                    .isInstanceOfSatisfying(WorkflowException.class, error -> assertThat(error.code())
                            .isEqualTo(WorkflowErrorCode.CODEC_MISMATCH));
            try (Connection connection = second.runtime().connections().openConnection();
                    Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE workflow_run SET state_codec_version=1, state_hash='" + "f".repeat(64)
                        + "' WHERE workflow_run_id='" + waiting.id().value() + "'");
            }
            assertThatThrownBy(() -> second.workflows().find(waiting.id()))
                    .isInstanceOf(SqliteStoreException.class)
                    .extracting(error -> ((SqliteStoreException) error).failure())
                    .isEqualTo(SqliteStoreFailure.WORKFLOW_CORRUPTION);
        }
    }

    @Test
    void recoversChildCreatedBeforeParentLinkWithoutReplayingTheChild() {
        CompiledWorkflowDefinition definition = subgraphDefinition(false);
        AtomicInteger calls = new AtomicInteger();
        WorkflowActionGateway action = (runId, node, state) -> {
            calls.incrementAndGet();
            return new WorkflowStateDelta(Map.of("count", 6));
        };
        WorkflowRunSnapshot parent;
        try (SqliteWorkflowStoreFoundation first = SqliteWorkflowStoreTestSupport.foundation(directory)) {
            assertThatThrownBy(() -> runtime(
                                    first,
                                    definition,
                                    action,
                                    once(WorkflowFailurePoint.AFTER_SUBGRAPH_CREATED),
                                    "first")
                            .start(start(definition, Map.of("count", 1), "subgraph-crash")))
                    .isInstanceOf(SimulatedCrash.class);
            parent = first.workflows().recoverable().stream()
                    .map(run -> run.snapshot())
                    .filter(snapshot -> snapshot.parent().isEmpty())
                    .findFirst()
                    .orElseThrow();
            assertThat(parent.activeSubgraph()).isPresent();
            assertThat(calls).hasValue(0);
        }

        try (SqliteWorkflowStoreFoundation second = SqliteWorkflowStoreTestSupport.foundation(directory)) {
            WorkflowRunSnapshot recovered = runtime(second, definition, action, WorkflowFailureInjector.NONE, "second")
                    .recover(parent.id());
            assertThat(recovered.status()).isEqualTo(WorkflowStatus.COMPLETED);
            assertThat(recovered.state().values()).containsEntry("count", 6);
            assertThat(calls).hasValue(1);
        }
    }

    @Test
    void recoversCompletedChildBeforeParentStateMergeWithoutReplay() {
        CompiledWorkflowDefinition definition = subgraphDefinition(false);
        AtomicInteger calls = new AtomicInteger();
        WorkflowActionGateway action = (runId, node, state) -> {
            calls.incrementAndGet();
            return new WorkflowStateDelta(Map.of("count", 8));
        };
        WorkflowRunSnapshot parent;
        try (SqliteWorkflowStoreFoundation first = SqliteWorkflowStoreTestSupport.foundation(directory)) {
            assertThatThrownBy(() -> runtime(
                                    first,
                                    definition,
                                    action,
                                    once(WorkflowFailurePoint.AFTER_SUBGRAPH_CHILD_COMPLETED),
                                    "first")
                            .start(start(definition, Map.of("count", 1), "subgraph-child-completed")))
                    .isInstanceOf(SimulatedCrash.class);
            parent = first.workflows().recoverable().stream()
                    .map(run -> run.snapshot())
                    .filter(snapshot -> snapshot.parent().isEmpty())
                    .findFirst()
                    .orElseThrow();
            assertThat(calls).hasValue(1);
        }
        try (SqliteWorkflowStoreFoundation second = SqliteWorkflowStoreTestSupport.foundation(directory)) {
            WorkflowRunSnapshot recovered = runtime(second, definition, action, WorkflowFailureInjector.NONE, "second")
                    .recover(parent.id());
            assertThat(recovered.status()).isEqualTo(WorkflowStatus.COMPLETED);
            assertThat(recovered.state().values()).containsEntry("count", 8);
            assertThat(calls).hasValue(1);
        }
    }

    @Test
    void resumesChildWaitAcrossRestartAndPropagatesParentCancellation() {
        CompiledWorkflowDefinition definition = subgraphDefinition(true);
        WorkflowRunSnapshot waiting;
        io.haifa.agent.orchestration.api.WorkflowRunId childId;
        try (SqliteWorkflowStoreFoundation first = SqliteWorkflowStoreTestSupport.foundation(directory)) {
            waiting = runtime(first, definition, noAction(), WorkflowFailureInjector.NONE, "first")
                    .start(start(definition, Map.of("count", 1), "subgraph-wait"));
            assertThat(waiting.status()).isEqualTo(WorkflowStatus.WAITING);
            childId = waiting.activeSubgraph().orElseThrow().runId();
            assertThat(first.workflows().find(childId).orElseThrow().snapshot().parent())
                    .isPresent();
        }

        try (SqliteWorkflowStoreFoundation second = SqliteWorkflowStoreTestSupport.foundation(directory)) {
            DurableWorkflowRuntime runtime =
                    runtime(second, definition, noAction(), WorkflowFailureInjector.NONE, "second");
            WorkflowRunSnapshot completed = runtime.resume(new WorkflowResumeRequest(
                    waiting.id(),
                    waiting.activeWait().orElseThrow().id(),
                    waiting.revision(),
                    new WorkflowSignalId("child-signal"),
                    "child-resume",
                    new WorkflowStateDelta(Map.of("choice", true))));
            assertThat(completed.status()).isEqualTo(WorkflowStatus.COMPLETED);
            assertThat(runtime.find(childId).orElseThrow().status()).isEqualTo(WorkflowStatus.COMPLETED);
        }

        Path cancelDirectory = directory.resolve("cancel");
        try {
            java.nio.file.Files.createDirectory(cancelDirectory);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
        try (SqliteWorkflowStoreFoundation third = SqliteWorkflowStoreTestSupport.foundation(cancelDirectory)) {
            DurableWorkflowRuntime runtime =
                    runtime(third, definition, noAction(), WorkflowFailureInjector.NONE, "third");
            WorkflowRunSnapshot parent = runtime.start(start(definition, Map.of("count", 1), "cancel-parent"));
            var child = parent.activeSubgraph().orElseThrow().runId();
            runtime.cancel(new io.haifa.agent.orchestration.api.WorkflowCancelRequest(parent.id(), "cancel-subgraph"));
            assertThat(runtime.find(child).orElseThrow().status()).isEqualTo(WorkflowStatus.CANCELLED);
        }

        Path timeoutDirectory = directory.resolve("timeout");
        try {
            java.nio.file.Files.createDirectory(timeoutDirectory);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
        try (SqliteWorkflowStoreFoundation fourth = SqliteWorkflowStoreTestSupport.foundation(timeoutDirectory)) {
            DurableWorkflowRuntime runtime =
                    runtime(fourth, definition, noAction(), WorkflowFailureInjector.NONE, "fourth");
            WorkflowRunSnapshot parent = runtime.start(start(definition, Map.of("count", 1), "timeout-parent"));
            var child = parent.activeSubgraph().orElseThrow().runId();
            WorkflowRunSnapshot timedOut = runtime.timeout(new WorkflowTimeoutRequest(parent.id(), "timeout-subgraph"));
            assertThat(timedOut.status()).isEqualTo(WorkflowStatus.TIMED_OUT);
            assertThat(runtime.timeout(new WorkflowTimeoutRequest(parent.id(), "timeout-subgraph")))
                    .isEqualTo(timedOut);
            assertThat(runtime.find(child).orElseThrow().status()).isEqualTo(WorkflowStatus.TIMED_OUT);
        }

        Path resumeCrashDirectory = directory.resolve("resume-crash");
        try {
            java.nio.file.Files.createDirectory(resumeCrashDirectory);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
        WorkflowRunSnapshot crashWaiting;
        WorkflowResumeRequest crashResume;
        try (SqliteWorkflowStoreFoundation fifth = SqliteWorkflowStoreTestSupport.foundation(resumeCrashDirectory)) {
            crashWaiting = runtime(fifth, definition, noAction(), WorkflowFailureInjector.NONE, "fifth")
                    .start(start(definition, Map.of("count", 1), "resume-crash-parent"));
            crashResume = new WorkflowResumeRequest(
                    crashWaiting.id(),
                    crashWaiting.activeWait().orElseThrow().id(),
                    crashWaiting.revision(),
                    new WorkflowSignalId("resume-crash-child-signal"),
                    "resume-crash-child",
                    WorkflowStateDelta.empty());
            assertThatThrownBy(() -> runtime(
                                    fifth,
                                    definition,
                                    noAction(),
                                    once(WorkflowFailurePoint.AFTER_RESUME_CONSUMED),
                                    "fifth-resume")
                            .resume(crashResume))
                    .isInstanceOf(SimulatedCrash.class);
        }
        try (SqliteWorkflowStoreFoundation sixth = SqliteWorkflowStoreTestSupport.foundation(resumeCrashDirectory)) {
            DurableWorkflowRuntime runtime =
                    runtime(sixth, definition, noAction(), WorkflowFailureInjector.NONE, "sixth");
            WorkflowRunSnapshot recovered = runtime.recover(crashWaiting.id());
            assertThat(recovered.status()).isEqualTo(WorkflowStatus.COMPLETED);
            assertThat(runtime.resume(crashResume)).isEqualTo(recovered);
        }
    }

    private DurableWorkflowRuntime runtime(
            SqliteWorkflowStoreFoundation foundation,
            CompiledWorkflowDefinition definition,
            WorkflowActionGateway actions,
            WorkflowFailureInjector failures,
            String idPrefix) {
        return runtime(foundation, definition, actions, failures, idPrefix, BINDING);
    }

    private DurableWorkflowRuntime runtime(
            SqliteWorkflowStoreFoundation foundation,
            CompiledWorkflowDefinition definition,
            WorkflowActionGateway actions,
            WorkflowFailureInjector failures,
            String idPrefix,
            WorkflowPersistenceBinding binding) {
        return runtime(foundation, definition, actions, NO_AGENT, failures, idPrefix, binding);
    }

    private DurableWorkflowRuntime runtime(
            SqliteWorkflowStoreFoundation foundation,
            CompiledWorkflowDefinition definition,
            WorkflowActionGateway actions,
            WorkflowAgentGateway agents,
            WorkflowConditionEvaluator conditions,
            WorkflowFailureInjector failures,
            String idPrefix,
            WorkflowPersistenceBinding binding) {
        AtomicInteger sequence = new AtomicInteger();
        IdentifierGenerator ids = () -> idPrefix + '-' + sequence.incrementAndGet();
        return new DurableWorkflowRuntime(
                List.of(definition),
                actions,
                agents,
                conditions,
                ids,
                TIME,
                foundation.workflows(),
                foundation.unitOfWork(),
                binding,
                failures);
    }

    private DurableWorkflowRuntime runtime(
            SqliteWorkflowStoreFoundation foundation,
            CompiledWorkflowDefinition definition,
            WorkflowActionGateway actions,
            WorkflowAgentGateway agents,
            WorkflowFailureInjector failures,
            String idPrefix,
            WorkflowPersistenceBinding binding) {
        return runtime(
                foundation, definition, actions, agents, (condition, state) -> false, failures, idPrefix, binding);
    }

    private static DurableWorkflowAgentGateway agentGateway(
            SqliteWorkflowStoreFoundation foundation, boolean recoverable) {
        return new DurableWorkflowAgentGateway() {
            @Override
            public AgentRunId start(
                    io.haifa.agent.orchestration.api.WorkflowRunId workflowRunId,
                    WorkflowNodeDefinition node,
                    WorkflowState state) {
                return SqliteWorkflowAggregateTestData.prepareRun(foundation.runtime())
                        .id();
            }

            @Override
            public AgentExecution await(
                    io.haifa.agent.orchestration.api.WorkflowRunId workflowRunId,
                    WorkflowNodeDefinition node,
                    WorkflowState state,
                    AgentRunId agentRunId) {
                return new AgentExecution(agentRunId, new WorkflowStateDelta(Map.of("count", 9)));
            }

            @Override
            public java.util.Optional<AgentExecution> recover(
                    io.haifa.agent.orchestration.api.WorkflowRunId workflowRunId,
                    WorkflowNodeDefinition node,
                    WorkflowState state,
                    AgentRunId agentRunId) {
                return recoverable
                        ? java.util.Optional.of(
                                new AgentExecution(agentRunId, new WorkflowStateDelta(Map.of("count", 9))))
                        : java.util.Optional.empty();
            }
        };
    }

    private static WorkflowFailureInjector once(WorkflowFailurePoint expected) {
        AtomicBoolean fired = new AtomicBoolean();
        return point -> {
            if (point == expected && fired.compareAndSet(false, true)) throw new SimulatedCrash();
        };
    }

    private static WorkflowActionGateway noAction() {
        return (runId, node, state) -> WorkflowStateDelta.empty();
    }

    private static CompiledWorkflowDefinition actionDefinition() {
        return compile(
                "durable-action",
                "action",
                List.of(WorkflowNodeDefinition.action("action", "side-effect"), terminal()),
                List.of(WorkflowEdge.unconditional("action", "end")),
                Set.of(WorkflowCapability.SEQUENCE));
    }

    private static CompiledWorkflowDefinition waitDefinition() {
        return compile(
                "durable-wait",
                "wait",
                List.of(
                        WorkflowNodeDefinition.control("wait", WorkflowNodeType.WAIT),
                        WorkflowNodeDefinition.action("after", "after"),
                        terminal()),
                List.of(WorkflowEdge.unconditional("wait", "after"), WorkflowEdge.unconditional("after", "end")),
                Set.of(WorkflowCapability.INTERRUPTION));
    }

    private static CompiledWorkflowDefinition agentDefinition() {
        return compile(
                "durable-agent",
                "agent",
                List.of(WorkflowNodeDefinition.agentRun("agent", "fixture-agent"), terminal()),
                List.of(WorkflowEdge.unconditional("agent", "end")),
                Set.of(WorkflowCapability.SEQUENCE));
    }

    private static CompiledWorkflowDefinition forkDefinition() {
        return compile(
                "durable-fork",
                "fork",
                List.of(
                        WorkflowNodeDefinition.control("fork", WorkflowNodeType.FORK_ALL),
                        WorkflowNodeDefinition.action("right", "right"),
                        WorkflowNodeDefinition.action("left", "left"),
                        WorkflowNodeDefinition.control("join", WorkflowNodeType.JOIN_ALL),
                        terminal()),
                List.of(
                        WorkflowEdge.branch("fork", "right", 2),
                        WorkflowEdge.branch("fork", "left", 1),
                        WorkflowEdge.unconditional("right", "join"),
                        WorkflowEdge.unconditional("left", "join"),
                        WorkflowEdge.unconditional("join", "end")),
                Set.of(WorkflowCapability.FIXED_ALL_OF));
    }

    private static CompiledWorkflowDefinition dynamicForkDefinition() {
        return compile(
                "durable-dynamic-fork",
                "fork",
                List.of(
                        WorkflowNodeDefinition.control("fork", WorkflowNodeType.FORK_DYNAMIC),
                        WorkflowNodeDefinition.action("right", "right"),
                        WorkflowNodeDefinition.action("left", "left"),
                        WorkflowNodeDefinition.control("join", WorkflowNodeType.JOIN_ALL),
                        terminal()),
                List.of(
                        WorkflowEdge.dynamicBranch("fork", "right", "right-selected", 2),
                        WorkflowEdge.dynamicBranch("fork", "left", "left-selected", 1),
                        WorkflowEdge.unconditional("right", "join"),
                        WorkflowEdge.unconditional("left", "join"),
                        WorkflowEdge.unconditional("join", "end")),
                Set.of(WorkflowCapability.DYNAMIC_FAN_OUT));
    }

    private static CompiledWorkflowDefinition anyOfDefinition() {
        return compile(
                "durable-any-of",
                "fork",
                List.of(
                        WorkflowNodeDefinition.control("fork", WorkflowNodeType.FORK_ANY),
                        WorkflowNodeDefinition.action("first", "first"),
                        WorkflowNodeDefinition.action("second", "second"),
                        WorkflowNodeDefinition.action("third", "third"),
                        WorkflowNodeDefinition.control("join", WorkflowNodeType.JOIN_ANY),
                        terminal()),
                List.of(
                        WorkflowEdge.branch("fork", "third", 2),
                        WorkflowEdge.branch("fork", "second", 1),
                        WorkflowEdge.branch("fork", "first", 0),
                        WorkflowEdge.unconditional("first", "join"),
                        WorkflowEdge.unconditional("second", "join"),
                        WorkflowEdge.unconditional("third", "join"),
                        WorkflowEdge.unconditional("join", "end")),
                Set.of(WorkflowCapability.ANY_OF));
    }

    private static CompiledWorkflowDefinition subgraphDefinition(boolean waiting) {
        WorkflowDefinition child = new WorkflowDefinition(
                new WorkflowDefinitionId(waiting ? "durable-child-wait" : "durable-child"),
                new WorkflowDefinitionVersion(1),
                SCHEMA,
                new WorkflowNodeId(waiting ? "wait" : "action"),
                waiting
                        ? List.of(
                                WorkflowNodeDefinition.control("wait", WorkflowNodeType.WAIT),
                                WorkflowNodeDefinition.action("after", "after"),
                                terminal())
                        : List.of(WorkflowNodeDefinition.action("action", "child-action"), terminal()),
                waiting
                        ? List.of(
                                WorkflowEdge.unconditional("wait", "after"), WorkflowEdge.unconditional("after", "end"))
                        : List.of(WorkflowEdge.unconditional("action", "end")),
                WorkflowLimits.defaults(),
                waiting ? Set.of(WorkflowCapability.INTERRUPTION) : Set.of(WorkflowCapability.SEQUENCE));
        DefaultWorkflowDefinitionCompiler compiler = new DefaultWorkflowDefinitionCompiler();
        var childRef = compiler.compile(child).reference();
        WorkflowDefinition parent = new WorkflowDefinition(
                new WorkflowDefinitionId(waiting ? "durable-parent-wait" : "durable-parent"),
                new WorkflowDefinitionVersion(1),
                SCHEMA,
                new WorkflowNodeId("sub"),
                List.of(
                        WorkflowNodeDefinition.subgraph(
                                "sub",
                                new WorkflowSubgraphBinding(
                                        childRef,
                                        new WorkflowStateMapping(Map.of("count", "count"), Map.of("count", "count")))),
                        terminal()),
                List.of(WorkflowEdge.unconditional("sub", "end")),
                WorkflowLimits.defaults(),
                Set.of(WorkflowCapability.SUBGRAPH));
        return compiler.compile(parent, List.of(child));
    }

    private static CompiledWorkflowDefinition subgraphForkDefinition() {
        WorkflowDefinition child = new WorkflowDefinition(
                new WorkflowDefinitionId("durable-branch-child"),
                new WorkflowDefinitionVersion(1),
                SCHEMA,
                new WorkflowNodeId("action"),
                List.of(WorkflowNodeDefinition.action("action", "increment"), terminal()),
                List.of(WorkflowEdge.unconditional("action", "end")),
                WorkflowLimits.defaults(),
                Set.of(WorkflowCapability.SEQUENCE));
        DefaultWorkflowDefinitionCompiler compiler = new DefaultWorkflowDefinitionCompiler();
        var childRef = compiler.compile(child).reference();
        WorkflowDefinition parent = new WorkflowDefinition(
                new WorkflowDefinitionId("durable-branch-parent"),
                new WorkflowDefinitionVersion(1),
                SCHEMA,
                new WorkflowNodeId("fork"),
                List.of(
                        WorkflowNodeDefinition.control("fork", WorkflowNodeType.FORK_ALL),
                        WorkflowNodeDefinition.subgraph(
                                "left-sub",
                                new WorkflowSubgraphBinding(
                                        childRef,
                                        new WorkflowStateMapping(Map.of("left", "count"), Map.of("count", "left")))),
                        WorkflowNodeDefinition.subgraph(
                                "right-sub",
                                new WorkflowSubgraphBinding(
                                        childRef,
                                        new WorkflowStateMapping(Map.of("right", "count"), Map.of("count", "right")))),
                        WorkflowNodeDefinition.control("join", WorkflowNodeType.JOIN_ALL),
                        terminal()),
                List.of(
                        WorkflowEdge.branch("fork", "right-sub", 2),
                        WorkflowEdge.branch("fork", "left-sub", 1),
                        WorkflowEdge.unconditional("left-sub", "join"),
                        WorkflowEdge.unconditional("right-sub", "join"),
                        WorkflowEdge.unconditional("join", "end")),
                WorkflowLimits.defaults(),
                Set.of(WorkflowCapability.FIXED_ALL_OF, WorkflowCapability.SUBGRAPH));
        return compiler.compile(parent, List.of(child));
    }

    private static CompiledWorkflowDefinition compile(
            String id,
            String entry,
            List<WorkflowNodeDefinition> nodes,
            List<WorkflowEdge> edges,
            Set<WorkflowCapability> capabilities) {
        return new DefaultWorkflowDefinitionCompiler()
                .compile(new WorkflowDefinition(
                        new WorkflowDefinitionId(id),
                        new WorkflowDefinitionVersion(1),
                        SCHEMA,
                        new WorkflowNodeId(entry),
                        nodes,
                        edges,
                        WorkflowLimits.defaults(),
                        capabilities));
    }

    private static WorkflowStartRequest start(
            CompiledWorkflowDefinition definition, Map<String, Object> values, String key) {
        return new WorkflowStartRequest(definition.reference(), new WorkflowState(SCHEMA, values), key);
    }

    private static WorkflowNodeDefinition terminal() {
        return WorkflowNodeDefinition.control("end", WorkflowNodeType.TERMINAL);
    }

    private static final class SimulatedCrash extends RuntimeException {}
}
