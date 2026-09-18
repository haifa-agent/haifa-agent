package io.haifa.agent.application.project.product.coding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.error.AgentError;
import io.haifa.agent.core.error.AgentErrorCode;
import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.message.MessageStatus;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import io.haifa.agent.runtime.core.storage.SessionMessageDraft;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CodingSessionHistoryServiceTest {
    private static final AgentSessionId SESSION_ID = new AgentSessionId("session-1");
    private final InMemoryRuntimeStore messages = new InMemoryRuntimeStore();
    private final AtomicInteger authorizations = new AtomicInteger();

    @Test
    void projectsOnlyRedactedUserVisibleConversationMessages() {
        append("user", new AgentRunId("run-1"), MessageRole.USER, MessageVisibility.USER_VISIBLE, "my secret");
        append("system", new AgentRunId("run-1"), MessageRole.SYSTEM, MessageVisibility.AGENT_VISIBLE, "hidden");
        append("assistant", new AgentRunId("run-1"), MessageRole.ASSISTANT, MessageVisibility.USER_VISIBLE, "done");
        var service = service(runId -> Optional.empty());

        CodingSessionHistoryPage page = service.recent(SESSION_ID, 100);

        assertThat(authorizations).hasValue(1);
        assertThat(page.earlierHistoryAvailable()).isFalse();
        assertThat(page.items())
                .extracting(CodingSessionHistoryItem::kind, CodingSessionHistoryItem::body)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(CodingSessionHistoryItem.Kind.USER, "my ***"),
                        org.assertj.core.groups.Tuple.tuple(CodingSessionHistoryItem.Kind.ASSISTANT, "done"));
    }

    @Test
    void addsASafeFailureSummaryWhenTheRunHasNoAssistantAnswer() {
        AgentRunId runId = new AgentRunId("run-failed");
        append("user", runId, MessageRole.USER, MessageVisibility.USER_VISIBLE, "try it");
        AgentError error = new AgentError(
                AgentErrorCode.MODEL_CALL_FAILED, Map.of(), "diagnostic-1", Instant.parse("2026-08-07T00:00:01Z"));
        AgentRunSnapshot failed = new AgentRunSnapshot(
                runId,
                AgentRunStatus.FAILED,
                2,
                Instant.parse("2026-08-07T00:00:02Z"),
                Optional.empty(),
                Optional.of(error),
                Optional.empty());

        CodingSessionHistoryPage page =
                service(requested -> Optional.of(failed)).recent(SESSION_ID, 100);

        assertThat(page.items()).hasSize(2);
        assertThat(page.items().getLast().kind()).isEqualTo(CodingSessionHistoryItem.Kind.ERROR);
        assertThat(page.items().getLast().body())
                .isEqualTo("[MODEL_CALL_FAILED] Model call failed · Diagnostic ID: diagnostic-1");
    }

    @Test
    void returnsTheLatestBoundedWindowAndMarksEarlierHistory() {
        for (int index = 1; index <= 105; index++) {
            append(
                    "message-" + index,
                    new AgentRunId("run-" + index),
                    MessageRole.USER,
                    MessageVisibility.USER_VISIBLE,
                    "message " + index);
        }

        CodingSessionHistoryPage page = service(runId -> Optional.empty()).recent(SESSION_ID, 100);

        assertThat(page.earlierHistoryAvailable()).isTrue();
        assertThat(page.items()).hasSize(100);
        assertThat(page.items().getFirst().body()).isEqualTo("message 6");
        assertThat(page.items().getLast().body()).isEqualTo("message 105");
    }

    @Test
    void validatesTheProductHistoryLimitBeforeReading() {
        assertThatThrownBy(() -> service(runId -> Optional.empty()).recent(SESSION_ID, 101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 100");
        assertThat(authorizations).hasValue(0);
    }

    private CodingSessionHistoryService service(
            java.util.function.Function<AgentRunId, Optional<AgentRunSnapshot>> runFinder) {
        return new CodingSessionHistoryService(
                ignored -> authorizations.incrementAndGet(),
                messages,
                runFinder,
                value -> value.replace("secret", "***"));
    }

    private void append(String id, AgentRunId runId, MessageRole role, MessageVisibility visibility, String text) {
        messages.appendSessionMessage(new SessionMessageDraft(
                new AgentMessageId(id),
                SESSION_ID,
                Optional.of(runId),
                Optional.empty(),
                role,
                MessageStatus.COMPLETED,
                visibility,
                java.util.List.of(new TextPart(text, "plain")),
                Map.of(),
                Instant.parse("2026-08-07T00:00:00Z")));
    }
}
