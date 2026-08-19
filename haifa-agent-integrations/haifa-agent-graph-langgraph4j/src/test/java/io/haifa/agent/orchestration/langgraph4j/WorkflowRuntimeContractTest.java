package io.haifa.agent.orchestration.langgraph4j;

import static org.assertj.core.api.Assertions.assertThat;

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
import io.haifa.agent.orchestration.api.WorkflowLimits;
import io.haifa.agent.orchestration.api.WorkflowNodeDefinition;
import io.haifa.agent.orchestration.api.WorkflowNodeId;
import io.haifa.agent.orchestration.api.WorkflowNodeType;
import io.haifa.agent.orchestration.api.WorkflowResumeRequest;
import io.haifa.agent.orchestration.api.WorkflowRunSnapshot;
import io.haifa.agent.orchestration.api.WorkflowRuntime;
import io.haifa.agent.orchestration.api.WorkflowSignalId;
import io.haifa.agent.orchestration.api.WorkflowStartRequest;
import io.haifa.agent.orchestration.api.WorkflowState;
import io.haifa.agent.orchestration.api.WorkflowStateDelta;
import io.haifa.agent.orchestration.api.WorkflowStateSchema;
import io.haifa.agent.orchestration.api.WorkflowStatus;
import io.haifa.agent.orchestration.core.DefaultWorkflowDefinitionCompiler;
import io.haifa.agent.orchestration.core.InMemoryWorkflowRuntime;
import io.haifa.agent.orchestration.core.spi.WorkflowActionGateway;
import io.haifa.agent.orchestration.core.spi.WorkflowAgentGateway;
import io.haifa.agent.orchestration.core.spi.WorkflowConditionEvaluator;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Runs the same frozen fixtures against the M1 reference engine and the M2 Provider Adapter. */
class WorkflowRuntimeContractTest {
    private static final WorkflowStateSchema SCHEMA =
            new WorkflowStateSchema("provider-tck", 1, Set.of("count", "choice", "left", "right", "agent"), 16, 4, 128);
    private static final WorkflowActionGateway NO_ACTION = (runId, node, state) -> WorkflowStateDelta.empty();
    private static final WorkflowAgentGateway NO_AGENT = (runId, node, state) ->
            new WorkflowAgentGateway.AgentExecution(new AgentRunId("unused-agent"), WorkflowStateDelta.empty());
    private static final WorkflowConditionEvaluator NO_CONDITION = (condition, state) -> false;

    @Test
    void sequenceHasEquivalentStateAttemptsAndEvents() {
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

        assertEquivalent(compiled, actions, NO_AGENT, NO_CONDITION, Map.of(), "sequence-start");
    }

    @Test
    void conditionHasEquivalentFrozenRoute() {
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

        assertEquivalent(compiled, NO_ACTION, NO_AGENT, conditions, Map.of("choice", true), "condition-start");
    }

    @Test
    void boundedLoopHasEquivalentLimitFailure() {
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

        RuntimePair pair = pair(compiled, increment, NO_AGENT, alwaysContinue);
        WorkflowRunSnapshot reference = pair.reference().start(start(compiled, Map.of(), "loop-start"));
        WorkflowRunSnapshot provider = pair.provider().start(start(compiled, Map.of(), "loop-start"));

        assertThat(reference.status()).isEqualTo(WorkflowStatus.FAILED);
        assertSnapshotsAndEventsEqual(pair, reference, provider);
    }

    @Test
    void fixedAllOfHasEquivalentDeterministicMerge() {
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

        assertEquivalent(compiled, actions, NO_AGENT, NO_CONDITION, Map.of(), "parallel-start");
    }

    @Test
    void waitResumeAndDuplicateSignalAreEquivalent() {
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
        RuntimePair pair = pair(compiled, NO_ACTION, NO_AGENT, NO_CONDITION);
        WorkflowRunSnapshot referenceWaiting = pair.reference().start(start(compiled, Map.of(), "wait-start"));
        WorkflowRunSnapshot providerWaiting = pair.provider().start(start(compiled, Map.of(), "wait-start"));
        assertThat(providerWaiting).isEqualTo(referenceWaiting);
        WorkflowResumeRequest referenceRequest = resume(referenceWaiting);
        WorkflowResumeRequest providerRequest = resume(providerWaiting);

        WorkflowRunSnapshot reference = pair.reference().resume(referenceRequest);
        WorkflowRunSnapshot provider = pair.provider().resume(providerRequest);

        assertThat(pair.reference().resume(referenceRequest)).isEqualTo(reference);
        assertThat(pair.provider().resume(providerRequest)).isEqualTo(provider);
        assertSnapshotsAndEventsEqual(pair, reference, provider);
    }

    @Test
    void agentGatewayAndWaitingCancellationAreEquivalent() {
        CompiledWorkflowDefinition agentDefinition = compile(
                "agent",
                "agent",
                List.of(WorkflowNodeDefinition.agentRun("agent", "binding:assistant:v1"), terminal()),
                List.of(WorkflowEdge.unconditional("agent", "end")),
                Set.of(WorkflowCapability.SEQUENCE),
                WorkflowLimits.defaults());
        WorkflowAgentGateway agent = (runId, node, state) -> new WorkflowAgentGateway.AgentExecution(
                new AgentRunId("agent-run-42"), new WorkflowStateDelta(Map.of("agent", "done")));
        assertEquivalent(agentDefinition, NO_ACTION, agent, NO_CONDITION, Map.of(), "agent-start");

        CompiledWorkflowDefinition waitDefinition = compile(
                "cancel",
                "wait",
                List.of(WorkflowNodeDefinition.control("wait", WorkflowNodeType.WAIT), terminal()),
                List.of(WorkflowEdge.unconditional("wait", "end")),
                Set.of(WorkflowCapability.INTERRUPTION),
                WorkflowLimits.defaults());
        RuntimePair pair = pair(waitDefinition, NO_ACTION, NO_AGENT, NO_CONDITION);
        WorkflowRunSnapshot referenceWaiting = pair.reference().start(start(waitDefinition, Map.of(), "cancel-start"));
        WorkflowRunSnapshot providerWaiting = pair.provider().start(start(waitDefinition, Map.of(), "cancel-start"));
        WorkflowCancelRequest referenceCancel = new WorkflowCancelRequest(referenceWaiting.id(), "cancel-1");
        WorkflowCancelRequest providerCancel = new WorkflowCancelRequest(providerWaiting.id(), "cancel-1");

        WorkflowRunSnapshot reference = pair.reference().cancel(referenceCancel);
        WorkflowRunSnapshot provider = pair.provider().cancel(providerCancel);

        assertThat(pair.reference().cancel(referenceCancel)).isEqualTo(reference);
        assertThat(pair.provider().cancel(providerCancel)).isEqualTo(provider);
        assertSnapshotsAndEventsEqual(pair, reference, provider);
    }

    private static void assertEquivalent(
            CompiledWorkflowDefinition compiled,
            WorkflowActionGateway actions,
            WorkflowAgentGateway agents,
            WorkflowConditionEvaluator conditions,
            Map<String, Object> initialState,
            String idempotencyKey) {
        RuntimePair pair = pair(compiled, actions, agents, conditions);
        WorkflowRunSnapshot reference = pair.reference().start(start(compiled, initialState, idempotencyKey));
        WorkflowRunSnapshot provider = pair.provider().start(start(compiled, initialState, idempotencyKey));
        assertSnapshotsAndEventsEqual(pair, reference, provider);
    }

    private static void assertSnapshotsAndEventsEqual(
            RuntimePair pair, WorkflowRunSnapshot reference, WorkflowRunSnapshot provider) {
        assertThat(provider).isEqualTo(reference);
        assertThat(pair.provider().events(provider.id(), 0, 100))
                .isEqualTo(pair.reference().events(reference.id(), 0, 100));
    }

    private static RuntimePair pair(
            CompiledWorkflowDefinition definition,
            WorkflowActionGateway actions,
            WorkflowAgentGateway agents,
            WorkflowConditionEvaluator conditions) {
        return new RuntimePair(
                runtime(false, definition, actions, agents, conditions),
                runtime(true, definition, actions, agents, conditions));
    }

    private static WorkflowRuntime runtime(
            boolean provider,
            CompiledWorkflowDefinition definition,
            WorkflowActionGateway actions,
            WorkflowAgentGateway agents,
            WorkflowConditionEvaluator conditions) {
        AtomicInteger ids = new AtomicInteger();
        IdentifierGenerator identifiers = () -> "workflow-id-" + ids.incrementAndGet();
        TimeProvider time = () -> Instant.parse("2026-08-19T00:00:00.123456Z");
        if (provider) {
            return new LangGraph4jWorkflowRuntime(
                    List.of(definition), actions, agents, conditions, identifiers, time, Runnable::run);
        }
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

    private static WorkflowResumeRequest resume(WorkflowRunSnapshot waiting) {
        return new WorkflowResumeRequest(
                waiting.id(),
                waiting.activeWait().orElseThrow().id(),
                waiting.revision(),
                new WorkflowSignalId("signal-1"),
                "resume-1",
                new WorkflowStateDelta(Map.of("choice", true)));
    }

    private static WorkflowNodeDefinition terminal() {
        return WorkflowNodeDefinition.control("end", WorkflowNodeType.TERMINAL);
    }

    private record RuntimePair(WorkflowRuntime reference, WorkflowRuntime provider) {}
}
