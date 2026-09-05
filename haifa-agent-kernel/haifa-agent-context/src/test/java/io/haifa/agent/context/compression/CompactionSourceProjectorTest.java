package io.haifa.agent.context.compression;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.content.ToolCallPart;
import io.haifa.agent.core.content.ToolResultPart;
import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.message.MessageStatus;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.ToolCallId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CompactionSourceProjectorTest {

    @Test
    void projectsUserAndAssistantMessagesWithAliasesAndFiltersHidden() {
        AgentSessionId session = new AgentSessionId("session-1");
        AgentMessage user = new AgentMessage(
                new AgentMessageId("msg-1"),
                session,
                Optional.empty(),
                Optional.empty(),
                MessageRole.USER,
                MessageStatus.COMPLETED,
                MessageVisibility.USER_VISIBLE,
                1L,
                List.of(new TextPart("Please build the water treatment report using ENV-ACCEPT-V3", "plain")),
                Map.of(),
                Instant.EPOCH);

        AgentMessage hidden = new AgentMessage(
                new AgentMessageId("msg-2"),
                session,
                Optional.empty(),
                Optional.empty(),
                MessageRole.USER,
                MessageStatus.COMPLETED,
                MessageVisibility.INTERNAL,
                2L,
                List.of(new TextPart("hidden internal note", "plain")),
                Map.of(),
                Instant.EPOCH);

        AgentMessage assistant = new AgentMessage(
                new AgentMessageId("msg-3"),
                session,
                Optional.empty(),
                Optional.empty(),
                MessageRole.ASSISTANT,
                MessageStatus.COMPLETED,
                MessageVisibility.AGENT_VISIBLE,
                3L,
                List.of(
                        new TextPart("I will structure the draft accordingly.", "plain"),
                        new ToolCallPart(new ToolCallId("tool-1"), new ProviderToolCallCorrelationId("c-1"), "extract_records", "1.0")),
                Map.of(),
                Instant.EPOCH);

        AgentMessage toolResult = new AgentMessage(
                new AgentMessageId("msg-4"),
                session,
                Optional.empty(),
                Optional.empty(),
                MessageRole.TOOL,
                MessageStatus.COMPLETED,
                MessageVisibility.USER_VISIBLE,
                4L,
                List.of(new ToolResultPart(new ToolCallId("tool-1"), new ProviderToolCallCorrelationId("c-1"), "Extracted COD 42 mg/L, NH3-N 6.5 mg/L")),
                Map.of(),
                Instant.EPOCH);

        ProjectedCompactionSource result = CompactionSourceProjector.project(List.of(user, hidden, assistant, toolResult));

        assertThat(result.sourceMessageIds()).containsExactly(user.id(), assistant.id(), toolResult.id());
        assertThat(result.messageAliases()).containsEntry("m001", user.id());
        assertThat(result.messageAliases()).containsEntry("m002", assistant.id());
        assertThat(result.messageAliases()).containsEntry("m003", toolResult.id());
        assertThat(result.toolAliases()).containsEntry("t001", new ToolCallId("tool-1"));
        assertThat(result.toolOutcomeReferences()).containsExactly(new ToolCallId("tool-1"));

        String text = result.safeConversationText();
        assertThat(text).contains("[m001 user completed] Please build the water treatment report");
        assertThat(text).contains("[m002 assistant completed] I will structure the draft accordingly. [tool-call t001: extract_records]");
        assertThat(text).contains("[m003 tool completed] [tool-outcome t001: Extracted COD 42 mg/L, NH3-N 6.5 mg/L]");
        assertThat(text).doesNotContain("hidden internal note");
    }

    @Test
    void stripsThinkingTagsCredentialsAndContinuationTokens() {
        AgentSessionId session = new AgentSessionId("session-1");
        AgentMessage message = new AgentMessage(
                new AgentMessageId("msg-1"),
                session,
                Optional.empty(),
                Optional.empty(),
                MessageRole.ASSISTANT,
                MessageStatus.COMPLETED,
                MessageVisibility.USER_VISIBLE,
                1L,
                List.of(new TextPart("Here is the key: sk-abcdef1234567890abcdef12345 and token Bearer my_secret_bearer_token_xyz. "
                        + "<think>internal reasoning to be stripped</think> "
                        + "Also PROTECTED_CONTINUATION:opaque_token_state final text.", "plain")),
                Map.of(),
                Instant.EPOCH);

        ProjectedCompactionSource result = CompactionSourceProjector.project(List.of(message));
        String text = result.safeConversationText();

        assertThat(text).doesNotContain("internal reasoning");
        assertThat(text).doesNotContain("<think>");
        assertThat(text).doesNotContain("sk-abcdef");
        assertThat(text).contains("[REDACTED_KEY]");
        assertThat(text).doesNotContain("my_secret_bearer_token_xyz");
        assertThat(text).contains("Bearer [REDACTED]");
        assertThat(text).doesNotContain("PROTECTED_CONTINUATION");
        assertThat(text).contains("[REDACTED_CONTINUATION]");
        assertThat(text).contains("final text.");
    }
}
