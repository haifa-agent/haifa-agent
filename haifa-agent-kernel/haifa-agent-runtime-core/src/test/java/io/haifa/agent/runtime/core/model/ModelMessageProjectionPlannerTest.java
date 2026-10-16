package io.haifa.agent.runtime.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.content.ToolCallPart;
import io.haifa.agent.core.content.ToolResultPart;
import io.haifa.agent.core.error.AgentError;
import io.haifa.agent.core.error.AgentErrorCode;
import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.message.MessageStatus;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.step.AgentStepId;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.RuntimeIdempotencyKey;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolExecutionError;
import io.haifa.agent.core.tool.ToolResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModelMessageProjectionPlannerTest {

    private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");
    private static final AgentSessionId SESSION_ID = new AgentSessionId("session-1");
    private static final AgentRunId RUN_ID = new AgentRunId("run-1");

    @Test
    void protectsQualifyingTailAndPrunesOlderGroupsInBatches() {
        List<AgentMessage> messages = new ArrayList<>();
        Map<ToolCallId, ToolCall> toolCalls = new HashMap<>();
        for (int i = 1; i <= 5; i++) {
            addCompletedToolTurn(
                    messages,
                    toolCalls,
                    "call-" + i,
                    "file_read",
                    Map.of("path", "src/File" + i + ".java"),
                    Map.of("content", "line\n".repeat(500)));
        }
        ModelMessageProjectionPlanner planner = new ModelMessageProjectionPlanner(nameIs("file_read"));

        ModelMessageProjectionPlan plan = planner.plan(messages, toolCalls::get);

        // 5 qualifying groups: protect the latest two, advance older groups in batches of two -> prune groups 1 and 2.
        assertThat(plan.prunedToolResults())
                .containsExactlyInAnyOrder(new ToolCallId("call-1"), new ToolCallId("call-2"));
        assertThat(plan.isToolResultPruned(new ToolCallId("call-3"))).isFalse();
        assertThat(plan.isToolResultPruned(new ToolCallId("call-4"))).isFalse();
        assertThat(plan.isToolResultPruned(new ToolCallId("call-5"))).isFalse();

        assertThat(plan.tokensSavedByPruning()).isPositive();
        assertThat(plan.projectedActiveTokens()).isLessThan(plan.rawActiveTokens());
    }

    @Test
    void preservesAnEntireGroupThatContainsAnIneligibleMember() {
        List<AgentMessage> messages = new ArrayList<>();
        Map<ToolCallId, ToolCall> toolCalls = new HashMap<>();
        addCompletedToolTurn(messages, toolCalls, "call-1", "file_read", Map.of("path", "a"), Map.of("data", "a"));
        addCompletedToolTurn(messages, toolCalls, "call-2", "file_read", Map.of("path", "b"), Map.of("data", "b"));
        addCompletedToolTurn(messages, toolCalls, "call-3", "file_write", Map.of("path", "c"), Map.of("data", "c"));
        addCompletedToolTurn(messages, toolCalls, "call-4", "file_read", Map.of("path", "d"), Map.of("data", "d"));
        addCompletedToolTurn(messages, toolCalls, "call-5", "file_read", Map.of("path", "e"), Map.of("data", "e"));
        addCompletedToolTurn(messages, toolCalls, "call-6", "file_read", Map.of("path", "f"), Map.of("data", "f"));
        ModelMessageProjectionPlanner planner = new ModelMessageProjectionPlanner(nameIs("file_read"));

        ModelMessageProjectionPlan plan = planner.plan(messages, toolCalls::get);

        // Qualifying groups are [1, 2, 4, 5, 6]; protecting the latest two leaves a batch of two (groups 1 and 2).
        assertThat(plan.prunedToolResults())
                .containsExactlyInAnyOrder(new ToolCallId("call-1"), new ToolCallId("call-2"));
        assertThat(plan.isToolResultPruned(new ToolCallId("call-3"))).isFalse();
        assertThat(plan.isToolResultPruned(new ToolCallId("call-4"))).isFalse();
    }

    @Test
    void emptyQualificationDisablesPruning() {
        List<AgentMessage> messages = new ArrayList<>();
        Map<ToolCallId, ToolCall> toolCalls = new HashMap<>();
        for (int i = 1; i <= 5; i++) {
            addCompletedToolTurn(
                    messages,
                    toolCalls,
                    "call-" + i,
                    "file_read",
                    Map.of("path", "src/File" + i + ".java"),
                    Map.of("content", "line\n".repeat(500)));
        }
        ModelMessageProjectionPlanner planner =
                new ModelMessageProjectionPlanner(ToolResultPruningQualifier.none());

        ModelMessageProjectionPlan plan = planner.plan(messages, toolCalls::get);

        assertThat(plan.prunedToolResults()).isEmpty();
        assertThat(plan.tokensSavedByPruning()).isZero();
        assertThat(plan.projectedActiveTokens()).isEqualTo(plan.rawActiveTokens());
    }

    @Test
    void neverQualifiesGroupsWithMissingOrFailedCalls() {
        List<AgentMessage> messages = new ArrayList<>();
        Map<ToolCallId, ToolCall> toolCalls = new HashMap<>();
        // Group 1 references a call that cannot be resolved.
        messages.add(assistant("ghost", "ghost-call", "file_read"));
        messages.add(tool("ghost-result", "ghost-call", "ghost summary"));
        // Group 2 is a completed failed read.
        addCompletedToolTurn(
                messages, toolCalls, "failed", "file_read", Map.of("path", "a"), Map.of("data", "x"), false);
        // Groups 3-5 are small successful reads (below the large-result threshold).
        for (int i = 3; i <= 5; i++) {
            addCompletedToolTurn(
                    messages, toolCalls, "call-" + i, "file_read", Map.of("path", "f" + i), Map.of("data", "small"));
        }
        ModelMessageProjectionPlanner planner = new ModelMessageProjectionPlanner(nameIs("file_read"));

        ModelMessageProjectionPlan plan = planner.plan(messages, toolCalls::get);

        assertThat(plan.prunedToolResults()).isEmpty();
        assertThat(plan.tokensSavedByPruning()).isZero();
    }

    @Test
    void estimatesTheWireRepresentationItActuallySends() {
        List<AgentMessage> messages = new ArrayList<>();
        Map<ToolCallId, ToolCall> toolCalls = new HashMap<>();
        for (int i = 1; i <= 4; i++) {
            addCompletedToolTurn(
                    messages,
                    toolCalls,
                    "big-" + i,
                    "file_read",
                    Map.of("path", "big/file" + i + ".json"),
                    Map.of("largeJson", "a".repeat(50_000)));
        }
        ModelMessageProjectionPlanner planner = new ModelMessageProjectionPlanner(nameIs("file_read"));

        ModelMessageProjectionPlan plan = planner.plan(messages, toolCalls::get);

        assertThat(plan.prunedToolResults())
                .containsExactlyInAnyOrder(new ToolCallId("big-1"), new ToolCallId("big-2"));
        assertThat(plan.rawActiveTokens()).isGreaterThan(plan.projectedActiveTokens());
        assertThat(plan.tokensSavedByPruning())
                .isEqualTo(plan.rawActiveTokens() - plan.projectedActiveTokens());
    }

    @Test
    void keepsOversizedArgumentsAtFullLengthForNonQualifyingResults() {
        List<AgentMessage> messages = new ArrayList<>();
        Map<ToolCallId, ToolCall> toolCalls = new HashMap<>();
        String hugeCode = "public class LargeCode {\n" + "    // logic\n".repeat(400) + "}\n";
        addCompletedToolTurn(
                messages,
                toolCalls,
                "write-1",
                "write_to_file",
                Map.of("codeContent", hugeCode, "targetFile", "LargeCode.java"),
                Map.of("success", true));
        for (int i = 2; i <= 5; i++) {
            addCompletedToolTurn(
                    messages, toolCalls, "read-" + i, "file_read", Map.of("path", "f" + i), Map.of("data", "d" + i));
        }
        ModelMessageProjectionPlanner planner = new ModelMessageProjectionPlanner(nameIs("file_read"));

        ModelMessageProjectionPlan plan = planner.plan(messages, toolCalls::get);

        assertThat(plan.prunedToolResults()).noneMatch(id -> id.value().equals("write-1"));
        assertThat(toolCalls.get(new ToolCallId("write-1")).arguments().values())
                .containsEntry("codeContent", hugeCode);
    }

    private static ToolResultPruningQualifier nameIs(String... names) {
        Set<String> selected = Set.of(names);
        return call -> selected.contains(call.toolName())
                && call.result().map(ToolResult::successful).orElse(false);
    }

    private static void addCompletedToolTurn(
            List<AgentMessage> messages,
            Map<ToolCallId, ToolCall> toolCalls,
            String callIdStr,
            String toolName,
            Map<String, Object> arguments,
            Map<String, Object> structuredData) {
        addCompletedToolTurn(messages, toolCalls, callIdStr, toolName, arguments, structuredData, true);
    }

    private static void addCompletedToolTurn(
            List<AgentMessage> messages,
            Map<ToolCallId, ToolCall> toolCalls,
            String callIdStr,
            String toolName,
            Map<String, Object> arguments,
            Map<String, Object> structuredData,
            boolean successful) {
        ToolCallId callId = new ToolCallId(callIdStr);
        ProviderToolCallCorrelationId corrId = new ProviderToolCallCorrelationId("corr-" + callIdStr);

        ToolCall toolCall = new ToolCall(
                callId,
                RUN_ID,
                new AgentStepId("step-" + callIdStr),
                corrId,
                new RuntimeIdempotencyKey("idempotency-" + callIdStr),
                toolName,
                "1.0.0",
                new ToolArguments("input.schema", "1.0.0", arguments),
                NOW);
        toolCall.beginValidation();
        toolCall.beginPolicyCheck();
        toolCall.start(NOW);

        ToolResult result = new ToolResult(successful, "summary " + callIdStr, structuredData, List.of(), List.of(), false);
        if (successful) {
            toolCall.complete(result, NOW);
        } else {
            toolCall.fail(
                    new ToolExecutionError(new AgentError(
                            AgentErrorCode.TOOL_BUSINESS_FAILURE, Map.of(), "failed " + callIdStr, NOW)),
                    result,
                    NOW);
        }
        toolCalls.put(callId, toolCall);

        messages.add(assistant(callIdStr, callIdStr, toolName, corrId, arguments));
        messages.add(tool(callIdStr, callIdStr, corrId, "summary " + callIdStr));
    }

    private static AgentMessage assistant(String suffix, String callIdStr, String toolName) {
        return assistant(suffix, callIdStr, toolName, new ProviderToolCallCorrelationId("corr-" + callIdStr), Map.of());
    }

    private static AgentMessage assistant(
            String suffix,
            String callIdStr,
            String toolName,
            ProviderToolCallCorrelationId corrId,
            Map<String, Object> arguments) {
        long seq = Math.abs(suffix.hashCode()) % 100_000 + 1;
        return new AgentMessage(
                new AgentMessageId("asst-" + suffix),
                SESSION_ID,
                Optional.of(RUN_ID),
                Optional.empty(),
                MessageRole.ASSISTANT,
                MessageStatus.COMPLETED,
                MessageVisibility.AGENT_VISIBLE,
                seq,
                List.of(new ToolCallPart(new ToolCallId(callIdStr), corrId, toolName, "1.0.0")),
                Map.of(),
                NOW.plusSeconds(seq));
    }

    private static AgentMessage tool(String suffix, String callIdStr, String summary) {
        return tool(suffix, callIdStr, new ProviderToolCallCorrelationId("corr-" + callIdStr), summary);
    }

    private static AgentMessage tool(
            String suffix, String callIdStr, ProviderToolCallCorrelationId corrId, String summary) {
        long seq = Math.abs(suffix.hashCode()) % 100_000 + 1;
        return new AgentMessage(
                new AgentMessageId("tool-" + suffix),
                SESSION_ID,
                Optional.of(RUN_ID),
                Optional.empty(),
                MessageRole.TOOL,
                MessageStatus.COMPLETED,
                MessageVisibility.AGENT_VISIBLE,
                seq,
                List.of(new ToolResultPart(new ToolCallId(callIdStr), corrId, summary)),
                Map.of(),
                NOW.plusSeconds(seq));
    }
}
