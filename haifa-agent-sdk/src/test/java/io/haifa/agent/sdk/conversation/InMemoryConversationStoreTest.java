package io.haifa.agent.sdk.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class InMemoryConversationStoreTest {
    @Test
    void enforcesCommandRevisionAndSingleActiveRunContract() {
        InMemoryConversationStore store = new InMemoryConversationStore();
        AgentSessionId sessionId = new AgentSessionId("session-1");
        Instant now = Instant.parse("2026-07-28T00:00:00Z");
        ConversationCommandBinding command = new ConversationCommandBinding(
                "caller",
                "submit",
                "key",
                "request-a",
                "dispatch",
                sessionId,
                Optional.empty(),
                false,
                OptionalLong.empty(),
                now);
        ConversationRecord conversation = store.create(new ConversationRecord(
                sessionId,
                new TenantRef("tenant"),
                new PrincipalRef("alice", "user"),
                "Title",
                ConversationStatus.ACTIVE,
                Optional.empty(),
                OptionalLong.empty(),
                Optional.empty(),
                now,
                now,
                0));

        assertThat(store.reserveCommand(command)).isEqualTo(command);
        assertThatThrownBy(() -> store.reserveCommand(new ConversationCommandBinding(
                        "caller",
                        "submit",
                        "key",
                        "request-b",
                        "dispatch-other",
                        sessionId,
                        Optional.empty(),
                        false,
                        OptionalLong.empty(),
                        now)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CONVERSATION_IDEMPOTENCY_CONFLICT");

        ConversationRecord reserved = store.reserveActive(sessionId, conversation.revision(), "dispatch", now);
        assertThatThrownBy(() -> store.reserveActive(sessionId, reserved.revision(), "dispatch-other", now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CONVERSATION_ACTIVE");
        ConversationRecord released =
                store.releasePendingDispatch(sessionId, "dispatch", reserved.revision(), now.plusSeconds(1));
        assertThat(released.activeRunId()).isEmpty();
        assertThat(released.activeDispatchKey()).isEmpty();
        ConversationRecord reservedAgain =
                store.reserveActive(sessionId, released.revision(), "dispatch", now.plusSeconds(2));
        ConversationRecord active =
                store.activateRun(sessionId, "dispatch", new AgentRunId("run-1"), 2, now.plusSeconds(3));
        ConversationCommandBinding completed =
                store.completeCommand("dispatch", Optional.of(new AgentRunId("run-1")), active.revision());

        assertThat(reservedAgain.activeDispatchKey()).contains("dispatch");
        assertThat(completed.completed()).isTrue();
        assertThat(store.findCommand("dispatch")).contains(completed);
        assertThatThrownBy(() -> store.rename(sessionId, 0, "stale", now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CONVERSATION_REVISION_STALE");
    }
}
