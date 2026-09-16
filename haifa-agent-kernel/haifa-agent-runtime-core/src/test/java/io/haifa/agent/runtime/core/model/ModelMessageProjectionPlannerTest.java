package io.haifa.agent.runtime.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.content.ToolCallPart;
import io.haifa.agent.core.content.ToolResultPart;
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
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
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
    void protectsTailAndAdvancesInBatches() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        ModelMessageProjectionPlanner planner = new ModelMessageProjectionPlanner(store);

        List<AgentMessage> messages = new ArrayList<>();
        Map<ToolCallId, ToolCall> toolCalls = new HashMap<>();

        // Add 5 completed file_read tool turns (each user turn -> assistant tool_call -> tool result)
        for (int i = 1; i <= 5; i++) {
            addCompletedToolTurn(
                    messages,
                    toolCalls,
                    "call-" + i,
                    "corr-" + i,
                    "file_read",
                    Map.of("path", "src/File" + i + ".java"),
                    "Read 500 lines",
                    Map.of("content", "line\n".repeat(500)),
                    true);
        }

        // Total 5 tool groups.
        // Tail protection protects latest 2 (groups 4 and 5).
        // Candidate groups = 5 - 2 = 3.
        // Batch step = 2 -> eligible = (3 / 2) * 2 = 2 groups (groups 1 and 2).
        ModelMessageProjectionPlan plan = planner.plan(messages, toolCalls::get, Set.of("file_read"), 100_000);

        assertThat(plan.prunedToolResults())
                .containsExactlyInAnyOrder(new ToolCallId("call-1"), new ToolCallId("call-2"));
        assertThat(plan.isToolResultPruned(new ToolCallId("call-1"))).isTrue();
        assertThat(plan.isToolResultPruned(new ToolCallId("call-2"))).isTrue();
        // Group 3 is protected by batch alignment (needs 6 total groups to advance to 4 pruned)
        assertThat(plan.isToolResultPruned(new ToolCallId("call-3"))).isFalse();
        // Groups 4 and 5 are protected by recent tail
        assertThat(plan.isToolResultPruned(new ToolCallId("call-4"))).isFalse();
        assertThat(plan.isToolResultPruned(new ToolCallId("call-5"))).isFalse();

        assertThat(plan.tokensSavedByPruning()).isPositive();
        assertThat(plan.projectedActiveTokens()).isLessThan(plan.rawActiveTokens());
        assertThat(plan.bypassCompactionRecommended()).isTrue();
    }

    @Test
    void onlyPrunesPureReadToolsAndPreservesWriteAndExecutionTools() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        ModelMessageProjectionPlanner planner = new ModelMessageProjectionPlanner(store);

        List<AgentMessage> messages = new ArrayList<>();
        Map<ToolCallId, ToolCall> toolCalls = new HashMap<>();

        // Add 4 tool turns (candidate count = 4 - 2 = 2, so 2 eligible for pruning):
        // Turn 1: file_read (pure read)
        addCompletedToolTurn(
                messages,
                toolCalls,
                "call-1",
                "corr-1",
                "file_read",
                Map.of("path", "src/A.java"),
                "read",
                Map.of("data", "x".repeat(1000)),
                true);
        // Turn 2: file_write (NOT pure read)
        addCompletedToolTurn(
                messages,
                toolCalls,
                "call-2",
                "corr-2",
                "file_write",
                Map.of("path", "src/B.java"),
                "written",
                Map.of("bytes", 100),
                true);
        // Turn 3: tail protected
        addCompletedToolTurn(
                messages,
                toolCalls,
                "call-3",
                "corr-3",
                "file_read",
                Map.of("path", "src/C.java"),
                "read",
                Map.of("data", "x".repeat(100)),
                true);
        // Turn 4: tail protected
        addCompletedToolTurn(
                messages,
                toolCalls,
                "call-4",
                "corr-4",
                "file_read",
                Map.of("path", "src/D.java"),
                "read",
                Map.of("data", "x".repeat(100)),
                true);

        ModelMessageProjectionPlan plan = planner.plan(messages, toolCalls::get, Set.of("file_read"), 0);

        // call-1 (file_read) is pruned; call-2 (file_write) is NOT pruned despite being in the eligible window!
        assertThat(plan.prunedToolResults()).containsExactly(new ToolCallId("call-1"));
        assertThat(plan.isToolResultPruned(new ToolCallId("call-2"))).isFalse();
    }

    @Test
    void truncatesOversizedArgumentsInEligibleHistoricalTurns() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        ModelMessageProjectionPlanner planner = new ModelMessageProjectionPlanner(store);

        List<AgentMessage> messages = new ArrayList<>();
        Map<ToolCallId, ToolCall> toolCalls = new HashMap<>();

        String hugeCode = "public class LargeCode {\n" + "    // some logic\n".repeat(100) + "}\n";
        assertThat(hugeCode.length()).isGreaterThan(1024);

        // Turn 1: write_to_file with huge code
        addCompletedToolTurn(
                messages,
                toolCalls,
                "call-write-1",
                "corr-w-1",
                "write_to_file",
                Map.of("TargetFile", "D:/project/LargeCode.java", "codeContent", hugeCode, "Description", "write file"),
                "File written",
                Map.of("success", true),
                true);
        // Turn 2: file_read
        addCompletedToolTurn(
                messages,
                toolCalls,
                "call-read-1",
                "corr-r-1",
                "file_read",
                Map.of("path", "src/Test.java"),
                "read",
                Map.of("data", "abc"),
                true);
        // Turns 3 and 4: tail
        addCompletedToolTurn(
                messages,
                toolCalls,
                "call-3",
                "corr-3",
                "file_read",
                Map.of("path", "src/C.java"),
                "read",
                Map.of("data", "c"),
                true);
        addCompletedToolTurn(
                messages,
                toolCalls,
                "call-4",
                "corr-4",
                "file_read",
                Map.of("path", "src/D.java"),
                "read",
                Map.of("data", "d"),
                true);

        ModelMessageProjectionPlan plan = planner.plan(messages, toolCalls::get, Set.of("file_read"), 0);

        ToolCallId writeCallId = new ToolCallId("call-write-1");
        assertThat(plan.isToolCallTruncated(writeCallId)).isTrue();
        Map<String, Object> truncatedArgs = plan.truncatedArguments(writeCallId);
        assertThat(truncatedArgs).isNotNull();
        assertThat(truncatedArgs.get("TargetFile")).isEqualTo("D:/project/LargeCode.java");
        assertThat(truncatedArgs.get("Description")).isEqualTo("write file");
        // codeContent is truncated with placeholder
        String truncatedCode = (String) truncatedArgs.get("codeContent");
        assertThat(truncatedCode)
                .contains("[Code content truncated")
                .contains("file written to D:/project/LargeCode.java");
    }

    @Test
    void bypassCompactionRecommendedWhenProjectedTokensDropBelowLimit() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        ModelMessageProjectionPlanner planner = new ModelMessageProjectionPlanner(store);

        List<AgentMessage> messages = new ArrayList<>();
        Map<ToolCallId, ToolCall> toolCalls = new HashMap<>();

        // Create 4 turns with massive file read payloads (50KB each)
        for (int i = 1; i <= 4; i++) {
            addCompletedToolTurn(
                    messages,
                    toolCalls,
                    "big-call-" + i,
                    "big-corr-" + i,
                    "file_read",
                    Map.of("path", "big/file" + i + ".json"),
                    "Read big file",
                    Map.of("largeJson", "a".repeat(50_000)),
                    true);
        }

        // Soft limit is 40,000 tokens.
        // Raw active tokens will be > 60,000 tokens (4 * 50KB).
        // Tail protection keeps the latest 2 turns (~33.5k tokens).
        // After pruning the first 2 big files (~33.5k tokens saved), projected tokens drop below 40,000.
        ModelMessageProjectionPlan plan = planner.plan(messages, toolCalls::get, Set.of("file_read"), 40_000);

        assertThat(plan.rawActiveTokens()).isGreaterThan(40_000L);
        assertThat(plan.tokensSavedByPruning()).isGreaterThan(20_000L);
        assertThat(plan.projectedActiveTokens()).isLessThan(40_000L);
        assertThat(plan.bypassCompactionRecommended()).isTrue();
    }

    private static void addCompletedToolTurn(
            List<AgentMessage> messages,
            Map<ToolCallId, ToolCall> toolCalls,
            String callIdStr,
            String corrIdStr,
            String toolName,
            Map<String, Object> arguments,
            String summary,
            Map<String, Object> structuredData,
            boolean successful) {
        ToolCallId callId = new ToolCallId(callIdStr);
        ProviderToolCallCorrelationId corrId = new ProviderToolCallCorrelationId(corrIdStr);

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

        ToolResult result = new ToolResult(successful, summary, structuredData, List.of(), List.of(), false);
        toolCall.complete(result, NOW);
        toolCalls.put(callId, toolCall);

        long seq = messages.size() + 1;
        // Assistant message with ToolCallPart
        messages.add(new AgentMessage(
                new AgentMessageId("asst-" + callIdStr),
                SESSION_ID,
                Optional.of(RUN_ID),
                Optional.empty(),
                MessageRole.ASSISTANT,
                MessageStatus.COMPLETED,
                MessageVisibility.AGENT_VISIBLE,
                seq,
                List.of(new ToolCallPart(callId, corrId, toolName, "1.0.0")),
                Map.of(),
                NOW.plusSeconds(seq)));

        seq = messages.size() + 1;
        // Tool message with ToolResultPart
        messages.add(new AgentMessage(
                new AgentMessageId("tool-" + callIdStr),
                SESSION_ID,
                Optional.of(RUN_ID),
                Optional.empty(),
                MessageRole.TOOL,
                MessageStatus.COMPLETED,
                MessageVisibility.AGENT_VISIBLE,
                seq,
                List.of(new ToolResultPart(callId, corrId, summary)),
                Map.of(),
                NOW.plusSeconds(seq)));
    }
}
