package io.haifa.agent.runtime.core.compaction;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.content.ContentPart;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.content.ToolCallPart;
import io.haifa.agent.core.content.ToolResultPart;
import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.message.MessageStatus;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.ToolCallId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CutoffPointValidatorTest {

    private static final AgentSessionId SESSION_ID = new AgentSessionId("session-cutoff");
    private static final AgentRunId RUN_ID = new AgentRunId("run-cutoff");

    @Test
    void returnsZeroForEmptyOrZeroRequested() {
        assertThat(CutoffPointValidator.validateCutoff(null, 5)).isEqualTo(0);
        assertThat(CutoffPointValidator.validateCutoff(List.of(), 5)).isEqualTo(0);
        assertThat(CutoffPointValidator.validateCutoff(List.of(userMsg("u1", 1)), 0))
                .isEqualTo(0);
        assertThat(CutoffPointValidator.validateCutoff(List.of(userMsg("u1", 1)), -1))
                .isEqualTo(0);
    }

    @Test
    void returnsSizeWhenCutoffAtOrBeyondSize() {
        List<AgentMessage> messages = List.of(userMsg("u1", 1), assistantMsg("a1", 2));
        assertThat(CutoffPointValidator.validateCutoff(messages, 2)).isEqualTo(2);
        assertThat(CutoffPointValidator.validateCutoff(messages, 10)).isEqualTo(2);
    }

    @Test
    void allowsCleanSplitOnUserOrAssistantWithoutTools() {
        List<AgentMessage> messages =
                List.of(userMsg("u1", 1), assistantMsg("a1", 2), userMsg("u2", 3), assistantMsg("a2", 4));

        assertThat(CutoffPointValidator.validateCutoff(messages, 2)).isEqualTo(2);
        assertThat(CutoffPointValidator.validateCutoff(messages, 3)).isEqualTo(3);
    }

    @Test
    void rollsBackWhenCutoffSplitsToolCallAndResult() {
        ToolCallId callId = new ToolCallId("call-1");
        List<AgentMessage> messages = List.of(
                userMsg("u1", 1), assistantWithCall("a1", 2, callId), toolResultMsg("t1", 3, callId), userMsg("u2", 4));

        // Cutoff at index 2 (compacts u1 and a1, retains t1 and u2):
        // a1 initiated call-1, but t1 has the result on the retained side.
        // It must roll back to 1 (before a1).
        assertThat(CutoffPointValidator.validateCutoff(messages, 2)).isEqualTo(1);
    }

    @Test
    void rollsBackWhenCutoffLandsDirectlyOnToolMessage() {
        ToolCallId callId = new ToolCallId("call-1");
        List<AgentMessage> messages = List.of(
                userMsg("u1", 1), assistantWithCall("a1", 2, callId), toolResultMsg("t1", 3, callId), userMsg("u2", 4));

        // Cutoff at index 2 lands directly on messages[2] which is t1 (TOOL).
        // It must roll back to 1.
        assertThat(CutoffPointValidator.validateCutoff(messages, 2)).isEqualTo(1);
    }

    @Test
    void allowsCleanSplitWhenToolPairIsComplete() {
        ToolCallId callId1 = new ToolCallId("call-1");
        ToolCallId callId2 = new ToolCallId("call-2");
        List<AgentMessage> messages = List.of(
                userMsg("u1", 1),
                assistantWithCall("a1", 2, callId1),
                toolResultMsg("t1", 3, callId1),
                userMsg("u2", 4),
                assistantWithCall("a2", 5, callId2),
                toolResultMsg("t2", 6, callId2));

        // Cutoff at index 3: [0, 3) contains u1, a1, t1 (call-1 completely closed)
        // [3, 6) contains u2, a2, t2 (call-2 completely retained)
        assertThat(CutoffPointValidator.validateCutoff(messages, 3)).isEqualTo(3);
    }

    @Test
    void rollsBackPastMultipleToolCallsToInitiatingAssistant() {
        ToolCallId callA = new ToolCallId("call-a");
        ToolCallId callB = new ToolCallId("call-b");
        AgentMessage assistantMulti = message(
                "asst-multi",
                MessageRole.ASSISTANT,
                2,
                List.of(
                        new ToolCallPart(callA, new ProviderToolCallCorrelationId("corr-a"), "toolA", "1.0"),
                        new ToolCallPart(callB, new ProviderToolCallCorrelationId("corr-b"), "toolB", "1.0")));

        List<AgentMessage> messages = List.of(
                userMsg("u1", 1),
                assistantMulti,
                toolResultMsg("t-a", 3, callA),
                toolResultMsg("t-b", 4, callB),
                userMsg("u2", 5));

        // If split is attempted at index 2 (after assistantMulti, before any results):
        assertThat(CutoffPointValidator.validateCutoff(messages, 2)).isEqualTo(1);

        // If split is attempted at index 3 (after t-a, but t-b is still on the retained side):
        assertThat(CutoffPointValidator.validateCutoff(messages, 3)).isEqualTo(1);

        // Once both t-a and t-b are included (cutoff at index 4):
        assertThat(CutoffPointValidator.validateCutoff(messages, 4)).isEqualTo(4);
    }

    private static AgentMessage userMsg(String id, long sequence) {
        return message(id, MessageRole.USER, sequence, List.of(new TextPart("User " + id, "plain")));
    }

    private static AgentMessage assistantMsg(String id, long sequence) {
        return message(id, MessageRole.ASSISTANT, sequence, List.of(new TextPart("Assistant " + id, "plain")));
    }

    private static AgentMessage assistantWithCall(String id, long sequence, ToolCallId callId) {
        return message(
                id,
                MessageRole.ASSISTANT,
                sequence,
                List.of(new ToolCallPart(callId, new ProviderToolCallCorrelationId("corr-" + id), "echo", "1.0")));
    }

    private static AgentMessage toolResultMsg(String id, long sequence, ToolCallId callId) {
        return message(
                id,
                MessageRole.TOOL,
                sequence,
                List.of(new ToolResultPart(
                        callId, new ProviderToolCallCorrelationId("corr-" + id), "Result for " + id)));
    }

    private static AgentMessage message(String id, MessageRole role, long sequence, List<ContentPart> contents) {
        return new AgentMessage(
                new AgentMessageId(id),
                SESSION_ID,
                Optional.of(RUN_ID),
                Optional.empty(),
                role,
                MessageStatus.COMPLETED,
                role == MessageRole.USER ? MessageVisibility.USER_VISIBLE : MessageVisibility.AGENT_VISIBLE,
                sequence,
                contents,
                Map.of(),
                Instant.parse("2026-07-21T00:00:00Z").plusSeconds(sequence));
    }
}
