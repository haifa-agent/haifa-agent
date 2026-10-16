package io.haifa.agent.runtime.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.content.ToolCallPart;
import io.haifa.agent.core.content.ToolResultPart;
import io.haifa.agent.core.error.AgentError;
import io.haifa.agent.core.error.AgentErrorCode;
import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.message.MessageStatus;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.step.AgentStepId;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.RuntimeIdempotencyKey;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolExecutionError;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.RuntimeOverrides;
import io.haifa.agent.runtime.core.execution.ManualExecutionScheduler;
import io.haifa.agent.runtime.core.model.FrozenBindingToolResultQualifier;
import io.haifa.agent.runtime.core.model.ModelMessageProjectionPlan;
import io.haifa.agent.runtime.core.model.ModelMessageProjectionPlanner;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import io.haifa.agent.runtime.core.storage.RunStateRepository;
import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
import io.haifa.agent.tool.api.FrozenToolBinding;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FrozenBindingToolResultQualifierTest {

    private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");
    private static final String LARGE = "x".repeat(20_000);

    private final FrozenBindingToolResultQualifier qualifier = new FrozenBindingToolResultQualifier();

    @Test
    void qualifiesOnlyLargeSuccessfulLocalPureReadsFromTheCallsOwnFrozenBinding() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentRun run = startRun(store);

        assertThat(qualifier.isPrunable(
                        completedRead(run, "file_read", "2.0.0", Map.of("payload", LARGE)),
                        bindingFor(store, run, "file_read")))
                .isTrue();
        assertThat(qualifier.isPrunable(
                        completedRead(run, "network_read", "1.0.0", Map.of("payload", LARGE)),
                        bindingFor(store, run, "network_read")))
                .isFalse();
        assertThat(qualifier.isPrunable(
                        completedRead(run, "file_write", "1.0.0", Map.of("payload", LARGE)),
                        bindingFor(store, run, "file_write")))
                .isFalse();
        assertThat(qualifier.isPrunable(
                        completedRead(run, "idempotent_write", "1.0.0", Map.of("payload", LARGE)),
                        bindingFor(store, run, "idempotent_write")))
                .isFalse();
        // Mismatched version for the same alias fails closed.
        assertThat(qualifier.isPrunable(
                        completedRead(run, "file_read", "9.9.9", Map.of("payload", LARGE)),
                        bindingFor(store, run, "file_read")))
                .isFalse();
        // Successful but below the large-result threshold.
        assertThat(qualifier.isPrunable(
                        completedRead(run, "file_read", "2.0.0", Map.of("payload", "tiny")),
                        bindingFor(store, run, "file_read")))
                .isFalse();
        // Failed outcome.
        assertThat(qualifier.isPrunable(failedRead(run, "file_read", "2.0.0"), bindingFor(store, run, "file_read")))
                .isFalse();
        // No binding resolved for the alias.
        assertThat(qualifier.isPrunable(
                        completedRead(run, "unknown_tool", "1.0.0", Map.of("payload", LARGE)),
                        bindingFor(store, run, "unknown_tool")))
                .isFalse();
    }

    @Test
    void failsClosedWithoutAResolvedBinding() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentRun run = startRun(store);

        assertThat(qualifier.isPrunable(completedRead(run, "file_read", "2.0.0", Map.of("payload", LARGE)), null))
                .isFalse();
    }

    @Test
    void repositoryQualifierDrivesPruningThroughThePlanner() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentRun run = startRun(store);
        ModelMessageProjectionPlanner planner =
                new ModelMessageProjectionPlanner(store, store, new FrozenBindingToolResultQualifier());
        ReadFixture fixture = readFixture(run, 4);

        ModelMessageProjectionPlan plan = planner.plan(fixture.messages(), fixture.calls()::get);

        assertThat(plan.prunedToolResults())
                .containsExactlyInAnyOrder(new ToolCallId("call-read-1"), new ToolCallId("call-read-2"));
        assertThat(plan.tokensSavedByPruning()).isPositive();
    }

    @Test
    void loadsEachRunOncePerOperationAndDiscardsTheIndexAfterward() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentRun run = startRun(store);
        AtomicInteger finds = new AtomicInteger();
        ModelMessageProjectionPlanner planner = new ModelMessageProjectionPlanner(
                countingRunRepository(store, finds), store, new FrozenBindingToolResultQualifier());
        ReadFixture fixture = readFixture(run, 4);

        planner.plan(fixture.messages(), fixture.calls()::get);
        assertThat(finds).as("one load per Run per operation").hasValue(1);

        // A second operation builds a fresh index: nothing is retained across operations.
        planner.plan(fixture.messages(), fixture.calls()::get);
        assertThat(finds).as("each operation reloads its own index").hasValue(2);
    }

    @Test
    void plansConcurrentlyThroughTheSameProductionPlannerWithoutSharedMutableState() throws Exception {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentRun run = startRun(store);
        CyclicBarrier firstLookup = new CyclicBarrier(2);
        RunStateRepository runs = (RunStateRepository) Proxy.newProxyInstance(
                RunStateRepository.class.getClassLoader(),
                new Class<?>[] {RunStateRepository.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("find")) {
                        firstLookup.await(10, TimeUnit.SECONDS);
                    }
                    return method.invoke(store, arguments);
                });
        ModelMessageProjectionPlanner planner =
                new ModelMessageProjectionPlanner(runs, store, new FrozenBindingToolResultQualifier());
        ReadFixture fixture = readFixture(run, 4);

        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> planner.plan(fixture.messages(), fixture.calls()::get));
            var second = pool.submit(() -> planner.plan(fixture.messages(), fixture.calls()::get));
            ModelMessageProjectionPlan firstPlan = first.get(15, TimeUnit.SECONDS);
            ModelMessageProjectionPlan secondPlan = second.get(15, TimeUnit.SECONDS);
            assertThat(firstPlan.prunedToolResults()).hasSize(2);
            assertThat(secondPlan.prunedToolResults()).hasSize(2);
        } finally {
            pool.shutdownNow();
        }
    }

    private record ReadFixture(List<AgentMessage> messages, Map<ToolCallId, ToolCall> calls) {}

    private static ReadFixture readFixture(AgentRun run, int count) {
        List<AgentMessage> messages = new ArrayList<>();
        Map<ToolCallId, ToolCall> calls = new HashMap<>();
        for (int i = 1; i <= count; i++) {
            ToolCall call = buildCall(run, "call-read-" + i, "file_read", "2.0.0", true, Map.of("payload", LARGE));
            calls.put(call.id(), call);
            messages.add(assistantMessage(call));
            messages.add(toolMessage(call));
        }
        return new ReadFixture(messages, calls);
    }

    private static FrozenToolBinding bindingFor(InMemoryRuntimeStore store, AgentRun run, String name) {
        return store.configuration(run.configurationSnapshot()).orElseThrow().toolBindings().stream()
                .filter(binding -> binding.alias().value().equals(name))
                .findFirst()
                .orElse(null);
    }

    private static RunStateRepository countingRunRepository(InMemoryRuntimeStore store, AtomicInteger finds) {
        return (RunStateRepository) Proxy.newProxyInstance(
                RunStateRepository.class.getClassLoader(),
                new Class<?>[] {RunStateRepository.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("find")) {
                        finds.incrementAndGet();
                    }
                    return method.invoke(store, arguments);
                });
    }

    private static ToolCall completedRead(AgentRun run, String toolName, String toolVersion, Map<String, Object> data) {
        return buildCall(run, toolName, toolVersion, true, data);
    }

    private static ToolCall failedRead(AgentRun run, String toolName, String toolVersion) {
        return buildCall(run, toolName, toolVersion, false, Map.of("payload", LARGE));
    }

    private static AgentMessage assistantMessage(ToolCall call) {
        return new AgentMessage(
                new AgentMessageId("asst-" + call.id().value()),
                new AgentSessionId("session-qualifier"),
                Optional.of(call.runId()),
                Optional.empty(),
                MessageRole.ASSISTANT,
                MessageStatus.COMPLETED,
                MessageVisibility.AGENT_VISIBLE,
                Math.abs(call.id().value().hashCode()) + 1L,
                List.of(new ToolCallPart(call.id(), call.providerCorrelationId(), call.toolName(), call.toolVersion())),
                Map.of(),
                NOW);
    }

    private static AgentMessage toolMessage(ToolCall call) {
        return new AgentMessage(
                new AgentMessageId("tool-" + call.id().value()),
                new AgentSessionId("session-qualifier"),
                Optional.of(call.runId()),
                Optional.empty(),
                MessageRole.TOOL,
                MessageStatus.COMPLETED,
                MessageVisibility.AGENT_VISIBLE,
                Math.abs(call.id().value().hashCode()) + 2L,
                List.of(new ToolResultPart(
                        call.id(),
                        call.providerCorrelationId(),
                        call.result().orElseThrow().summary())),
                Map.of(),
                NOW);
    }

    private static ToolCall buildCall(
            AgentRun run, String toolName, String toolVersion, boolean successful, Map<String, Object> data) {
        String suffix = toolName + "-" + toolVersion + "-" + successful;
        return buildCall(run, "call-" + suffix, toolName, toolVersion, successful, data);
    }

    private static ToolCall buildCall(
            AgentRun run,
            String callId,
            String toolName,
            String toolVersion,
            boolean successful,
            Map<String, Object> data) {
        ToolCall call = new ToolCall(
                new ToolCallId(callId),
                run.id(),
                new AgentStepId("step-" + callId),
                new ProviderToolCallCorrelationId("corr-" + callId),
                new RuntimeIdempotencyKey("idem-" + callId),
                toolName,
                toolVersion,
                new ToolArguments("schema", "1.0", Map.of("path", "file.txt")),
                NOW);
        call.beginValidation();
        call.beginPolicyCheck();
        call.start(NOW);
        ToolResult result = new ToolResult(successful, "summary", data, List.of(), List.of(), false);
        if (successful) {
            call.complete(result, NOW);
        } else {
            call.fail(
                    new ToolExecutionError(
                            new AgentError(AgentErrorCode.TOOL_BUSINESS_FAILURE, Map.of(), "failed", NOW)),
                    result,
                    NOW);
        }
        return call;
    }

    private static AgentRun startRun(InMemoryRuntimeStore store) {
        ManualExecutionScheduler scheduler = new ManualExecutionScheduler();
        AtomicInteger ids = new AtomicInteger();
        var runtime = TestToolPlatform.installDefinitions(
                        new RuntimeCoreBuilder(),
                        List.of(
                                TestToolPlatform.pureReadDefinition("file_read", "2.0.0", "haifa.file_read.input"),
                                TestToolPlatform.networkPureReadDefinition(
                                        "network_read", "1.0.0", "haifa.network_read.input"),
                                TestToolPlatform.sideEffectingDefinition(
                                        "file_write", "1.0.0", "haifa.file_write.input"),
                                TestToolPlatform.idempotentWriteDefinition(
                                        "idempotent_write", "1.0.0", "haifa.idempotent_write.input")),
                        request -> new ToolResult(true, "ok", Map.of(), List.of(), List.of(), false))
                .registerChatModel(
                        "openai-compatible",
                        "1.0.0",
                        request -> new AgentChatResponse(
                                "init",
                                "deepseek-v4-pro",
                                "hello",
                                List.of(),
                                ModelFinishReason.STOP,
                                ModelUsage.unpriced(1, 1),
                                "",
                                Map.of()))
                .persistence(RuntimePersistencePorts.inMemory(store))
                .scheduler(scheduler)
                .identifierGenerator(() -> "id-" + ids.incrementAndGet())
                .timeProvider(() -> NOW)
                .build();
        var accepted = runtime.start(new AgentRunRequest(
                "key-qualifier",
                new AgentDefinitionId("test-agent"),
                Optional.empty(),
                "default",
                new AgentSessionId("session-qualifier"),
                Optional.empty(),
                "objective",
                List.of(),
                RuntimeOverrides.NONE));
        return store.find(accepted.runId()).orElseThrow();
    }
}
