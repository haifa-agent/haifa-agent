package io.haifa.agent.orchestration.langgraph4j;

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
import io.haifa.agent.orchestration.api.WorkflowException;
import io.haifa.agent.orchestration.api.WorkflowLimits;
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
import io.haifa.agent.orchestration.core.DefaultWorkflowDefinitionCompiler;
import io.haifa.agent.orchestration.core.spi.WorkflowActionGateway;
import io.haifa.agent.orchestration.core.spi.WorkflowAgentGateway;
import io.haifa.agent.orchestration.core.spi.WorkflowConditionEvaluator;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class LangGraph4jWorkflowRuntimeTest {
    private static final WorkflowStateSchema SCHEMA =
            new WorkflowStateSchema("provider", 1, Set.of("left", "right", "value"), 16, 4, 128);
    private static final WorkflowAgentGateway NO_AGENT = (runId, node, state) ->
            new WorkflowAgentGateway.AgentExecution(new AgentRunId("unused-agent"), WorkflowStateDelta.empty());
    private static final WorkflowConditionEvaluator NO_CONDITION = (condition, state) -> false;

    @Test
    void fixedBranchesCommitTheSameProjectionAcrossOppositeCompletionOrders() throws Exception {
        WorkflowRunSnapshot rightFirst = runParallel("right", "left");
        WorkflowRunSnapshot leftFirst = runParallel("left", "right");

        assertThat(rightFirst).isEqualTo(leftFirst);
        assertThat(rightFirst.attempts())
                .extracting(attempt -> attempt.nodeId().value())
                .containsExactly("left", "right");
        assertThat(rightFirst.state().values()).containsEntry("left", "L").containsEntry("right", "R");
    }

    @Test
    void providerAndGatewayFailuresAreNormalizedWithoutProviderDetails() {
        CompiledWorkflowDefinition compiled = compile(
                "failure",
                "fail",
                List.of(
                        WorkflowNodeDefinition.action("fail", "failing-action"),
                        WorkflowNodeDefinition.control("end", WorkflowNodeType.TERMINAL)),
                List.of(WorkflowEdge.unconditional("fail", "end")),
                Set.of(WorkflowCapability.SEQUENCE));
        WorkflowActionGateway failure = (runId, node, state) -> {
            throw new IllegalStateException("provider detail must remain private");
        };
        LangGraph4jWorkflowRuntime runtime = runtime(compiled, failure, Runnable::run);

        WorkflowRunSnapshot result = runtime.start(start(compiled, "failure-start"));

        assertThat(result.status()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(result.failure()).get().satisfies(normalized -> {
            assertThat(normalized.code()).isEqualTo(WorkflowErrorCode.NODE_EXECUTION_FAILED);
            assertThat(normalized.operation()).isEqualTo("provider-execute");
        });
        assertThat(runtime.events(result.id(), 0, 100).getLast().attributes())
                .containsExactly(Map.entry("code", "NODE_EXECUTION_FAILED"));
    }

    @Test
    void outcomeUnknownFromHaifaGatewayIsPreserved() {
        CompiledWorkflowDefinition compiled = compile(
                "unknown",
                "action",
                List.of(
                        WorkflowNodeDefinition.action("action", "external-side-effect"),
                        WorkflowNodeDefinition.control("end", WorkflowNodeType.TERMINAL)),
                List.of(WorkflowEdge.unconditional("action", "end")),
                Set.of(WorkflowCapability.SEQUENCE));
        WorkflowActionGateway unknown = (runId, node, state) -> {
            throw new WorkflowException(WorkflowErrorCode.OUTCOME_UNKNOWN, "action", "result is unknown");
        };

        WorkflowRunSnapshot result = runtime(compiled, unknown, Runnable::run).start(start(compiled, "unknown-start"));

        assertThat(result.failure())
                .get()
                .extracting(failure -> failure.code())
                .isEqualTo(WorkflowErrorCode.OUTCOME_UNKNOWN);
        assertThat(result.attempts()).singleElement().satisfies(attempt -> assertThat(attempt.failureCode())
                .contains(WorkflowErrorCode.OUTCOME_UNKNOWN));
    }

    @ParameterizedTest
    @EnumSource(
            value = WorkflowCapability.class,
            names = {"DYNAMIC_FAN_OUT", "ANY_OF"})
    void deferredCapabilitiesFailBeforeProviderCompilation(WorkflowCapability unsupported) {
        CompiledWorkflowDefinition supported = compile(
                "unsupported",
                "action",
                List.of(
                        WorkflowNodeDefinition.action("action", "noop"),
                        WorkflowNodeDefinition.control("end", WorkflowNodeType.TERMINAL)),
                List.of(WorkflowEdge.unconditional("action", "end")),
                Set.of(WorkflowCapability.SEQUENCE));
        CompiledWorkflowDefinition forged =
                new CompiledWorkflowDefinition(supported.reference(), supported.definition(), Set.of(unsupported));

        assertThatThrownBy(() -> runtime(forged, (runId, node, state) -> WorkflowStateDelta.empty(), Runnable::run))
                .isInstanceOfSatisfying(WorkflowException.class, exception -> assertThat(exception.code())
                        .isEqualTo(WorkflowErrorCode.UNSUPPORTED_CAPABILITY));
    }

    @Test
    void executesStaticSubgraphThroughProviderGraph() {
        WorkflowDefinition child = definition(
                "child",
                "action",
                List.of(
                        WorkflowNodeDefinition.action("action", "increment"),
                        WorkflowNodeDefinition.control("end", WorkflowNodeType.TERMINAL)),
                List.of(WorkflowEdge.unconditional("action", "end")),
                Set.of(WorkflowCapability.SEQUENCE));
        DefaultWorkflowDefinitionCompiler compiler = new DefaultWorkflowDefinitionCompiler();
        var childRef = compiler.compile(child).reference();
        WorkflowDefinition parent = definition(
                "parent",
                "sub",
                List.of(
                        WorkflowNodeDefinition.subgraph(
                                "sub",
                                new WorkflowSubgraphBinding(
                                        childRef,
                                        new WorkflowStateMapping(Map.of("value", "value"), Map.of("value", "value")))),
                        WorkflowNodeDefinition.control("end", WorkflowNodeType.TERMINAL)),
                List.of(WorkflowEdge.unconditional("sub", "end")),
                Set.of(WorkflowCapability.SUBGRAPH));
        CompiledWorkflowDefinition compiled = compiler.compile(parent, List.of(child));
        WorkflowActionGateway increment = (runId, node, state) ->
                new WorkflowStateDelta(Map.of("value", ((Integer) state.values().get("value")) + 1));
        LangGraph4jWorkflowRuntime runtime = runtime(compiled, increment, Runnable::run);

        WorkflowRunSnapshot result = runtime.start(new WorkflowStartRequest(
                compiled.reference(), new WorkflowState(SCHEMA, Map.of("value", 3)), "subgraph-start"));

        assertThat(result.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(result.state().values()).containsEntry("value", 4);
        assertThat(result.attempts())
                .singleElement()
                .satisfies(attempt -> assertThat(attempt.nodeId().value()).isEqualTo("sub"));
    }

    @Test
    void resumesWaitInsideStaticSubgraph() {
        WorkflowDefinition child = definition(
                "child-wait",
                "wait",
                List.of(
                        WorkflowNodeDefinition.control("wait", WorkflowNodeType.WAIT),
                        WorkflowNodeDefinition.action("after", "after"),
                        WorkflowNodeDefinition.control("end", WorkflowNodeType.TERMINAL)),
                List.of(WorkflowEdge.unconditional("wait", "after"), WorkflowEdge.unconditional("after", "end")),
                Set.of(WorkflowCapability.INTERRUPTION));
        DefaultWorkflowDefinitionCompiler compiler = new DefaultWorkflowDefinitionCompiler();
        var childRef = compiler.compile(child).reference();
        WorkflowDefinition parent = definition(
                "parent-wait",
                "sub",
                List.of(
                        WorkflowNodeDefinition.subgraph(
                                "sub",
                                new WorkflowSubgraphBinding(
                                        childRef,
                                        new WorkflowStateMapping(Map.of("value", "value"), Map.of("value", "value")))),
                        WorkflowNodeDefinition.control("end", WorkflowNodeType.TERMINAL)),
                List.of(WorkflowEdge.unconditional("sub", "end")),
                Set.of(WorkflowCapability.SUBGRAPH));
        CompiledWorkflowDefinition compiled = compiler.compile(parent, List.of(child));
        LangGraph4jWorkflowRuntime runtime =
                runtime(compiled, (runId, node, state) -> WorkflowStateDelta.empty(), Runnable::run);
        WorkflowRunSnapshot waiting = runtime.start(new WorkflowStartRequest(
                compiled.reference(), new WorkflowState(SCHEMA, Map.of("value", 3)), "subgraph-wait"));

        WorkflowRunSnapshot result = runtime.resume(new WorkflowResumeRequest(
                waiting.id(),
                waiting.activeWait().orElseThrow().id(),
                waiting.revision(),
                new WorkflowSignalId("child-signal"),
                "child-resume",
                WorkflowStateDelta.empty()));

        assertThat(waiting.status()).isEqualTo(WorkflowStatus.WAITING);
        assertThat(result.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(result.activeSubgraph()).isEmpty();
    }

    private static WorkflowRunSnapshot runParallel(String firstCompletion, String secondCompletion) throws Exception {
        CompiledWorkflowDefinition compiled = compile(
                "parallel",
                "fork",
                List.of(
                        WorkflowNodeDefinition.control("fork", WorkflowNodeType.FORK_ALL),
                        WorkflowNodeDefinition.action("right", "right"),
                        WorkflowNodeDefinition.action("left", "left"),
                        WorkflowNodeDefinition.control("join", WorkflowNodeType.JOIN_ALL),
                        WorkflowNodeDefinition.control("end", WorkflowNodeType.TERMINAL)),
                List.of(
                        WorkflowEdge.branch("fork", "right", 2),
                        WorkflowEdge.branch("fork", "left", 1),
                        WorkflowEdge.unconditional("right", "join"),
                        WorkflowEdge.unconditional("left", "join"),
                        WorkflowEdge.unconditional("join", "end")),
                Set.of(WorkflowCapability.FIXED_ALL_OF));
        CountDownLatch entered = new CountDownLatch(2);
        Map<String, CountDownLatch> releases = Map.of("left", new CountDownLatch(1), "right", new CountDownLatch(1));
        Map<String, CountDownLatch> completed = Map.of("left", new CountDownLatch(1), "right", new CountDownLatch(1));
        WorkflowActionGateway controlled = (runId, node, state) -> {
            String id = node.id().value();
            entered.countDown();
            await(releases.get(id));
            completed.get(id).countDown();
            return new WorkflowStateDelta(id.equals("left") ? Map.of("left", "L") : Map.of("right", "R"));
        };
        ExecutorService branches = Executors.newFixedThreadPool(2);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            LangGraph4jWorkflowRuntime runtime = runtime(compiled, controlled, branches);
            CompletableFuture<WorkflowRunSnapshot> result =
                    CompletableFuture.supplyAsync(() -> runtime.start(start(compiled, "parallel-start")), caller);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            releases.get(firstCompletion).countDown();
            assertThat(completed.get(firstCompletion).await(5, TimeUnit.SECONDS))
                    .isTrue();
            releases.get(secondCompletion).countDown();
            return result.get(5, TimeUnit.SECONDS);
        } finally {
            caller.shutdownNow();
            branches.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test branch release timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test branch was interrupted", exception);
        }
    }

    private static LangGraph4jWorkflowRuntime runtime(
            CompiledWorkflowDefinition compiled,
            WorkflowActionGateway actions,
            java.util.concurrent.Executor executor) {
        AtomicInteger ids = new AtomicInteger();
        IdentifierGenerator identifiers = () -> "workflow-id-" + ids.incrementAndGet();
        TimeProvider time = () -> Instant.parse("2026-08-19T00:00:00.123456Z");
        return new LangGraph4jWorkflowRuntime(
                List.of(compiled), actions, NO_AGENT, NO_CONDITION, identifiers, time, executor);
    }

    private static CompiledWorkflowDefinition compile(
            String id,
            String entry,
            List<WorkflowNodeDefinition> nodes,
            List<WorkflowEdge> edges,
            Set<WorkflowCapability> capabilities) {
        return new DefaultWorkflowDefinitionCompiler().compile(definition(id, entry, nodes, edges, capabilities));
    }

    private static WorkflowDefinition definition(
            String id,
            String entry,
            List<WorkflowNodeDefinition> nodes,
            List<WorkflowEdge> edges,
            Set<WorkflowCapability> capabilities) {
        return new WorkflowDefinition(
                new WorkflowDefinitionId(id),
                new WorkflowDefinitionVersion(1),
                SCHEMA,
                new WorkflowNodeId(entry),
                nodes,
                edges,
                WorkflowLimits.defaults(),
                capabilities);
    }

    private static WorkflowStartRequest start(CompiledWorkflowDefinition compiled, String key) {
        return new WorkflowStartRequest(compiled.reference(), new WorkflowState(SCHEMA, Map.of()), key);
    }
}
