package io.haifa.agent.runtime.core.context;

import static org.assertj.core.api.Assertions.assertThat;

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

class AtomicMessageGroupsTest {
    private static final AgentSessionId SESSION = new AgentSessionId("atomic-session");
    private static final AgentRunId RUN = new AgentRunId("atomic-run");
    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");

    @Test
    void stopsAtTheFirstPointWhereEveryToolCallHasAResult() {
        ToolCallId first = new ToolCallId("call-1");
        ToolCallId second = new ToolCallId("call-2");
        ProviderToolCallCorrelationId firstCorrelation = new ProviderToolCallCorrelationId("provider-1");
        ProviderToolCallCorrelationId secondCorrelation = new ProviderToolCallCorrelationId("provider-2");
        AgentMessage calls = message(
                "calls",
                1,
                MessageRole.ASSISTANT,
                List.of(
                        new ToolCallPart(first, firstCorrelation, "read_file", "1.0"),
                        new ToolCallPart(second, secondCorrelation, "read_file", "1.0")));
        AgentMessage firstResult =
                message("result-1", 2, MessageRole.TOOL, List.of(new ToolResultPart(first, firstCorrelation, "one")));
        AgentMessage secondResult =
                message("result-2", 3, MessageRole.TOOL, List.of(new ToolResultPart(second, secondCorrelation, "two")));
        AgentMessage tail = message("tail", 4, MessageRole.USER, List.of(new TextPart("next", "plain")));

        AtomicMessageGroups.Result result = AtomicMessageGroups.group(List.of(calls, firstResult, secondResult, tail));

        assertThat(result.candidateScans()).isEqualTo(2);
        assertThat(result.groups()).containsExactly(List.of(calls, firstResult, secondResult), List.of(tail));
    }

    @Test
    void omitsAnIncompleteToolCallMessageFromTheProjection() {
        ToolCallId call = new ToolCallId("call-incomplete");
        ProviderToolCallCorrelationId correlation = new ProviderToolCallCorrelationId("provider-incomplete");
        AgentMessage incomplete = message(
                "incomplete",
                1,
                MessageRole.ASSISTANT,
                List.of(new ToolCallPart(call, correlation, "read_file", "1.0")));
        AgentMessage tail = message("tail", 2, MessageRole.USER, List.of(new TextPart("continue", "plain")));

        AtomicMessageGroups.Result result = AtomicMessageGroups.group(List.of(incomplete, tail));

        assertThat(result.groups()).containsExactly(List.of(tail));
    }

    private static AgentMessage message(
            String id, long sequence, MessageRole role, List<io.haifa.agent.core.content.ContentPart> contents) {
        return new AgentMessage(
                new AgentMessageId(id),
                SESSION,
                Optional.of(RUN),
                Optional.empty(),
                role,
                MessageStatus.COMPLETED,
                role == MessageRole.USER ? MessageVisibility.USER_VISIBLE : MessageVisibility.AGENT_VISIBLE,
                sequence,
                contents,
                Map.of(),
                NOW);
    }
}
