package io.haifa.agent.sdk.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.run.AgentRunId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConversationTurnTest {
    private static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");

    @Test
    void readProjectionRetainsExactLongTextBeyondFormerCap() {
        String first = "head:" + "x".repeat(69_995);
        ConversationTurn turn = new ConversationTurn(
                "msg-70k",
                MessageRole.ASSISTANT,
                Optional.<AgentRunId>empty(),
                1,
                List.of(new TextPart(first, "text/plain")),
                MessageVisibility.USER_VISIBLE,
                NOW);

        assertThat(turn.text()).isEqualTo(first);
    }

    @Test
    void mixedOrderedTextPartsBeyondFormerCapKeepFullTextInOrder() {
        String first = "A" + "x".repeat(32_000);
        String second = "y".repeat(33_000) + "B";
        ConversationTurn turn = new ConversationTurn(
                "msg-mixed",
                MessageRole.USER,
                Optional.<AgentRunId>empty(),
                2,
                List.of(new TextPart(first, "text/plain"), new TextPart(second, "text/plain")),
                MessageVisibility.USER_VISIBLE,
                NOW);

        assertThat(turn.text()).isEqualTo(first + "\n" + second);
    }

}
