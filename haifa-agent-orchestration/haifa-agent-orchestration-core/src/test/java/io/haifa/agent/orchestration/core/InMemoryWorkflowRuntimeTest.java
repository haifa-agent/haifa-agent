package io.haifa.agent.orchestration.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.orchestration.api.CompiledWorkflowDefinition;
import io.haifa.agent.orchestration.api.WorkflowCancelRequest;
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
import io.haifa.agent.orchestration.api.WorkflowStateSchema;
import io.haifa.agent.orchestration.api.WorkflowStatus;
import io.haifa.agent.orchestration.core.spi.WorkflowActionGateway;
import io.haifa.agent.orchestration.core.spi.WorkflowAgentGateway;
import io.haifa.agent.orchestration.core.spi.WorkflowConditionEvaluator;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class InMemoryWorkflowRuntimeTest {
    private static final WorkflowStateSchema SCHEMA =
            new WorkflowStateSchema("fixture", 1, Set.of("count", "choice", "left", "right", "agent"), 16, 4, 128);
    private static final WorkflowActionGateway NO_ACTION = (runId, node, state) -> WorkflowStateDelta.empty();
    private static final WorkflowAgentGateway NO_AGENT = (runId, node, state) ->
            new WorkflowAgentGateway.AgentExecution(new AgentRunId("unused-agent"), WorkflowStateDelta.empty());
    private static final WorkflowConditionEvaluator NO_CONDITION = (condition, state) -> false;

    @Test
    void executesSequenceAndReturnsMonotonicEvents() {
        CompiledWorkflowDefinition compiled = compile(
                "sequence",
                "first",
                List.of(
                        WorkflowNodeDefinition.action("first", "set-count"),
                        WorkflowNodeDefinition.action("second", "increment"),
                        terminal()),
                List.of(WorkflowEdge.unconditional("first", "second"), WorkflowEdge.unconditional("second", "end")),
                Set.of(WorkflowCapability.SEQUENCE),
                WorkflowLimits.defaults());
        WorkflowActionGateway actions = (runId, node, state) -> new WorkflowStateDelta(Map.of(
                "count",
                node.id().value().equals("first")
                        ? 1
                        : ((Integer) state.values().get("count")) + 1));
        InMemoryWorkflowRuntime runtime = runtime(compiled, actions, NO_AGENT, NO_CONDITION);

        WorkflowRunSnapshot result = runtime.start(start(compiled, Map.of(), "start-1"));

        assertThat(result.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(result.state().values()).containsEntry("count", 2);
        assertThat(result.attempts())
                .extracting(attempt -> attempt.nodeId().value())
                .containsExactly("first", "second");
        assertThat(runtime.events(result.id(), 0, 100))
                .extracting(event -> event.sequence())
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
    }

    @Test
    void selectsOneExplicitConditionalRoute() {
        CompiledWorkflowDefinition compiled = compile(
                "condition",
                "route",
                List.of(
                        WorkflowNodeDefinition.action("route", "route"),
                        WorkflowNodeDefinition.action("yes", "yes"),
                        WorkflowNodeDefinition.action("no", "no"),
                        terminal()),
                List.of(
                        WorkflowEdge.conditional("route", "yes", "chosen"),
                        WorkflowEdge.conditional("route", "no", "not-chosen"),
                        WorkflowEdge.unconditional("yes", "end"),
                        WorkflowEdge.unconditional("no", "end")),
                Set.of(WorkflowCapability.CONDITION),
                WorkflowLimits.defaults());
        WorkflowConditionEvaluator conditions = (condition, state) ->
                condition.equals("chosen") == (Boolean) state.values().get("choice");
        InMemoryWorkflowRuntime runtime = runtime(compiled, NO_ACTION, NO_AGENT, conditions);

        WorkflowRunSnapshot result = runtime.start(start(compiled, Map.of("choice", true), "start-2"));

        assertThat(result.attempts())
                .extracting(attempt -> attempt.nodeId().value())
                .containsExactly("route", "yes");
    }

    @Test
    void enforcesBoundedLoop() {
        CompiledWorkflowDefinition compiled = compile(
                "loop",
                "step",
                List.of(WorkflowNodeDefinition.action("step", "increment"), terminal()),
                List.of(
                        WorkflowEdge.conditional("step", "step", "continue"),
                        WorkflowEdge.conditional("step", "end", "done")),
                Set.of(WorkflowCapability.CONDITION, WorkflowCapability.BOUNDED_LOOP),
                new WorkflowLimits(2, 8, 2));
        WorkflowActionGateway increment = (runId, node, state) ->
                new WorkflowStateDelta(Map.of("count", ((Integer) state.values().getOrDefault("count", 0)) + 1));
        WorkflowConditionEvaluator alwaysContinue = (condition, state) -> condition.equals("continue");
        InMemoryWorkflowRuntime runtime = runtime(compiled, increment, NO_AGENT, alwaysContinue);

        WorkflowRunSnapshot result = runtime.start(start(compiled, Map.of(), "start-loop"));

        assertThat(result.status()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(result.failure())
                .get()
                .extracting(failure -> failure.code().name())
                .isEqualTo("ITERATION_LIMIT_EXCEEDED");
        assertThat(result.state().values()).containsEntry("count", 2);
    }

    @Test
    void mergesFixedAllOfBranchesIndependentOfDeclarationOrder() {
        CompiledWorkflowDefinition compiled = compile(
                "parallel",
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
                Set.of(WorkflowCapability.FIXED_ALL_OF),
                WorkflowLimits.defaults());
        WorkflowActionGateway actions = (runId, node, state) ->
                new WorkflowStateDelta(node.id().value().equals("left") ? Map.of("left", "L") : Map.of("right", "R"));
        InMemoryWorkflowRuntime runtime = runtime(compiled, actions, NO_AGENT, NO_CONDITION);

        WorkflowRunSnapshot result = runtime.start(start(compiled, Map.of(), "start-parallel"));

        assertThat(result.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(result.state().values()).containsEntry("left", "L").containsEntry("right", "R");
        assertThat(result.attempts())
                .extracting(attempt -> attempt.nodeId().value())
                .containsExactly("left", "right");
    }

    @Test
    void createsCheckpointAndMakesResumeIdempotent() {
        CompiledWorkflowDefinition compiled = compile(
                "wait",
                "wait",
                List.of(
                        WorkflowNodeDefinition.control("wait", WorkflowNodeType.WAIT),
                        WorkflowNodeDefinition.action("after", "after"),
                        terminal()),
                List.of(WorkflowEdge.unconditional("wait", "after"), WorkflowEdge.unconditional("after", "end")),
                Set.of(WorkflowCapability.INTERRUPTION),
                WorkflowLimits.defaults());
        InMemoryWorkflowRuntime runtime = runtime(compiled, NO_ACTION, NO_AGENT, NO_CONDITION);
        WorkflowRunSnapshot waiting = runtime.start(start(compiled, Map.of(), "start-wait"));
        WorkflowResumeRequest request = new WorkflowResumeRequest(
                waiting.id(),
                waiting.activeWait().orElseThrow().id(),
                waiting.revision(),
                new WorkflowSignalId("signal-1"),
                "resume-1",
                new WorkflowStateDelta(Map.of("choice", true)));

        WorkflowRunSnapshot first = runtime.resume(request);
        WorkflowRunSnapshot duplicate = runtime.resume(request);

        assertThat(waiting.status()).isEqualTo(WorkflowStatus.WAITING);
        assertThat(waiting.checkpoint()).isPresent();
        assertThat(first).isEqualTo(duplicate);
        assertThat(first.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(runtime.events(first.id(), 0, 100))
                .extracting(event -> event.type())
                .contains(WorkflowEventType.WAITING, WorkflowEventType.RESUMED);
        WorkflowResumeRequest conflicting = new WorkflowResumeRequest(
                request.runId(),
                request.waitId(),
                request.expectedRevision(),
                request.signalId(),
                request.idempotencyKey(),
                new WorkflowStateDelta(Map.of("choice", false)));
        assertThatThrownBy(() -> runtime.resume(conflicting))
                .isInstanceOfSatisfying(WorkflowException.class, exception -> assertThat(exception.code())
                        .isEqualTo(WorkflowErrorCode.IDEMPOTENCY_CONFLICT));
    }

    @Test
    void agentNodeKeepsAuthoritativeAgentRunIdentity() {
        CompiledWorkflowDefinition compiled = compile(
                "agent",
                "agent",
                List.of(WorkflowNodeDefinition.agentRun("agent", "binding:assistant:v1"), terminal()),
                List.of(WorkflowEdge.unconditional("agent", "end")),
                Set.of(WorkflowCapability.SEQUENCE),
                WorkflowLimits.defaults());
        AtomicReference<String> target = new AtomicReference<>();
        WorkflowAgentGateway agent = (runId, node, state) -> {
            target.set(node.targetReference().orElseThrow());
            return new WorkflowAgentGateway.AgentExecution(
                    new AgentRunId("agent-run-42"), new WorkflowStateDelta(Map.of("agent", "done")));
        };
        InMemoryWorkflowRuntime runtime = runtime(compiled, NO_ACTION, agent, NO_CONDITION);

        WorkflowRunSnapshot result = runtime.start(start(compiled, Map.of(), "start-agent"));

        assertThat(target).hasValue("binding:assistant:v1");
        assertThat(result.attempts().getFirst().agentRunId()).contains(new AgentRunId("agent-run-42"));
    }

    @Test
    void startIsIdempotent() {
        CompiledWorkflowDefinition compiled = compile(
                "idempotent",
                "first",
                List.of(WorkflowNodeDefinition.action("first", "noop"), terminal()),
                List.of(WorkflowEdge.unconditional("first", "end")),
                Set.of(WorkflowCapability.SEQUENCE),
                WorkflowLimits.defaults());
        InMemoryWorkflowRuntime runtime = runtime(compiled, NO_ACTION, NO_AGENT, NO_CONDITION);
        WorkflowStartRequest request = start(compiled, Map.of(), "same-start");

        assertThat(runtime.start(request).id()).isEqualTo(runtime.start(request).id());
    }

    @Test
    void cancelWaitingRunIsIdempotent() {
        CompiledWorkflowDefinition compiled = compile(
                "cancel",
                "wait",
                List.of(WorkflowNodeDefinition.control("wait", WorkflowNodeType.WAIT), terminal()),
                List.of(WorkflowEdge.unconditional("wait", "end")),
                Set.of(WorkflowCapability.INTERRUPTION),
                WorkflowLimits.defaults());
        InMemoryWorkflowRuntime runtime = runtime(compiled, NO_ACTION, NO_AGENT, NO_CONDITION);
        WorkflowRunSnapshot waiting = runtime.start(start(compiled, Map.of(), "start-cancel"));
        WorkflowCancelRequest request = new WorkflowCancelRequest(waiting.id(), "cancel-1");

        WorkflowRunSnapshot first = runtime.cancel(request);
        WorkflowRunSnapshot duplicate = runtime.cancel(request);

        assertThat(first).isEqualTo(duplicate);
        assertThat(first.status()).isEqualTo(WorkflowStatus.CANCELLED);
        assertThat(runtime.events(first.id(), 0, 100))
                .extracting(event -> event.type())
                .contains(WorkflowEventType.CANCELLED);
    }

    @Test
    void preservesOutcomeUnknownAsAnObservableFailureCode() {
        CompiledWorkflowDefinition compiled = compile(
                "unknown",
                "first",
                List.of(WorkflowNodeDefinition.action("first", "external-side-effect"), terminal()),
                List.of(WorkflowEdge.unconditional("first", "end")),
                Set.of(WorkflowCapability.SEQUENCE),
                WorkflowLimits.defaults());
        WorkflowActionGateway unknown = (runId, node, state) -> {
            throw new WorkflowException(
                    WorkflowErrorCode.OUTCOME_UNKNOWN, "action", "external side effect result is unknown");
        };
        InMemoryWorkflowRuntime runtime = runtime(compiled, unknown, NO_AGENT, NO_CONDITION);

        WorkflowRunSnapshot result = runtime.start(start(compiled, Map.of(), "start-unknown"));

        assertThat(result.status()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(result.failure())
                .get()
                .extracting(failure -> failure.code())
                .isEqualTo(WorkflowErrorCode.OUTCOME_UNKNOWN);
        assertThat(result.attempts()).singleElement().satisfies(attempt -> {
            assertThat(attempt.status()).isEqualTo(WorkflowNodeAttemptStatus.OUTCOME_UNKNOWN);
            assertThat(attempt.failureCode()).contains(WorkflowErrorCode.OUTCOME_UNKNOWN);
        });
    }

    private static InMemoryWorkflowRuntime runtime(
            CompiledWorkflowDefinition definition,
            WorkflowActionGateway actions,
            WorkflowAgentGateway agents,
            WorkflowConditionEvaluator conditions) {
        AtomicInteger ids = new AtomicInteger();
        IdentifierGenerator identifiers = () -> "workflow-id-" + ids.incrementAndGet();
        TimeProvider time = () -> Instant.parse("2026-08-19T00:00:00.123456Z");
        return new InMemoryWorkflowRuntime(List.of(definition), actions, agents, conditions, identifiers, time);
    }

    private static CompiledWorkflowDefinition compile(
            String id,
            String entry,
            List<WorkflowNodeDefinition> nodes,
            List<WorkflowEdge> edges,
            Set<WorkflowCapability> capabilities,
            WorkflowLimits limits) {
        return new DefaultWorkflowDefinitionCompiler()
                .compile(new WorkflowDefinition(
                        new WorkflowDefinitionId(id),
                        new WorkflowDefinitionVersion(1),
                        SCHEMA,
                        new WorkflowNodeId(entry),
                        nodes,
                        edges,
                        limits,
                        capabilities));
    }

    private static WorkflowStartRequest start(
            CompiledWorkflowDefinition definition, Map<String, Object> state, String key) {
        return new WorkflowStartRequest(definition.reference(), new WorkflowState(SCHEMA, state), key);
    }

    private static WorkflowNodeDefinition terminal() {
        return WorkflowNodeDefinition.control("end", WorkflowNodeType.TERMINAL);
    }
}
