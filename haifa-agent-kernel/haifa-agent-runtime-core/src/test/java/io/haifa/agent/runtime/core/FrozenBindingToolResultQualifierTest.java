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
import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FrozenBindingToolResultQualifierTest {

    private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");
    private static final String LARGE = "x".repeat(20_000);

    @Test
    void qualifiesOnlyLargeSuccessfulLocalPureReadsFromTheCallsOwnFrozenBinding() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentRun run = startRun(store);
        FrozenBindingToolResultQualifier qualifier = new FrozenBindingToolResultQualifier(store, store);

        assertThat(qualifier.isPrunable(completedRead(run, "file_read", "2.0.0", Map.of("payload", LARGE))))
                .isTrue();
        assertThat(qualifier.isPrunable(completedRead(run, "network_read", "1.0.0", Map.of("payload", LARGE))))
                .isFalse();
        assertThat(qualifier.isPrunable(completedRead(run, "file_write", "1.0.0", Map.of("payload", LARGE))))
                .isFalse();
        assertThat(qualifier.isPrunable(completedRead(run, "idempotent_write", "1.0.0", Map.of("payload", LARGE))))
                .isFalse();
        assertThat(qualifier.isPrunable(completedRead(run, "file_read", "9.9.9", Map.of("payload", LARGE))))
                .isFalse();
        assertThat(qualifier.isPrunable(completedRead(run, "file_read", "2.0.0", Map.of("payload", "tiny"))))
                .isFalse();
        assertThat(qualifier.isPrunable(failedRead(run, "file_read", "2.0.0"))).isFalse();
        assertThat(qualifier.isPrunable(completedRead(run, "unknown_tool", "1.0.0", Map.of("payload", LARGE))))
                .isFalse();
    }

    @Test
    void failsClosedWhenTheRunBindingCannotBeResolved() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentRun run = startRun(store);
        FrozenBindingToolResultQualifier qualifier = new FrozenBindingToolResultQualifier(store, store);

        ToolCall unattached = completedRead(run, "file_read", "2.0.0", Map.of("payload", LARGE));
        // A call whose Run was never persisted has no authoritative frozen binding.
        ToolCall otherRun = new ToolCall(
                new ToolCallId("other-run-call"),
                new io.haifa.agent.core.run.AgentRunId("run-absent"),
                new AgentStepId("step"),
                new ProviderToolCallCorrelationId("corr"),
                new RuntimeIdempotencyKey("idem"),
                "file_read",
                "2.0.0",
                new ToolArguments("schema", "1.0", Map.of()),
                NOW);
        otherRun.beginValidation();
        otherRun.beginPolicyCheck();
        otherRun.start(NOW);
        otherRun.complete(new ToolResult(true, "summary", Map.of("payload", LARGE), List.of(), List.of(), false), NOW);

        assertThat(qualifier.isPrunable(unattached)).isTrue();
        assertThat(qualifier.isPrunable(otherRun)).isFalse();
    }

    private static ToolCall completedRead(
            AgentRun run, String toolName, String toolVersion, Map<String, Object> data) {
        return buildCall(run, toolName, toolVersion, true, data);
    }

    private static ToolCall failedRead(AgentRun run, String toolName, String toolVersion) {
        return buildCall(run, toolName, toolVersion, false, Map.of("payload", LARGE));
    }

    @Test
    void repositoryQualifierDrivesPruningThroughThePlanner() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentRun run = startRun(store);
        ModelMessageProjectionPlanner planner =
                new ModelMessageProjectionPlanner(new FrozenBindingToolResultQualifier(store, store));

        List<AgentMessage> messages = new ArrayList<>();
        Map<ToolCallId, ToolCall> calls = new HashMap<>();
        for (int i = 1; i <= 4; i++) {
            ToolCall call =
                    buildCall(run, "call-wiring-" + i, "file_read", "2.0.0", true, Map.of("payload", LARGE));
            calls.put(call.id(), call);
            messages.add(assistantMessage(call));
            messages.add(toolMessage(call));
        }

        ModelMessageProjectionPlan plan = planner.plan(messages, calls::get);

        assertThat(plan.prunedToolResults())
                .containsExactlyInAnyOrder(new ToolCallId("call-wiring-1"), new ToolCallId("call-wiring-2"));
        assertThat(plan.tokensSavedByPruning()).isPositive();
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
                        call.id(), call.providerCorrelationId(), call.result().orElseThrow().summary())),
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
        String suffix = callId;
        ToolCall call = new ToolCall(
                new ToolCallId(callId),
                run.id(),
                new AgentStepId("step-" + suffix),
                new ProviderToolCallCorrelationId("corr-" + suffix),
                new RuntimeIdempotencyKey("idem-" + suffix),
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
                    new ToolExecutionError(new AgentError(AgentErrorCode.TOOL_BUSINESS_FAILURE, Map.of(), "failed", NOW)),
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
