package io.haifa.agent.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.content.ArtifactRefPart;
import io.haifa.agent.core.content.AssetRefPart;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.content.ToolCallPart;
import io.haifa.agent.core.content.ToolResultPart;
import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.message.MessageCursor;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.message.MessageStatus;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.reference.ArtifactRef;
import io.haifa.agent.core.reference.AssetRef;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.session.AgentSession;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.session.SessionScope;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.runtime.core.storage.SessionMessageDraft;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteSessionMessageRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-07-25T00:00:00Z");

    @Test
    void roundTripsAllContentPartsCursorsWindowsAndCommittedRedaction(@TempDir java.nio.file.Path directory) {
        SqliteStoreFoundation foundation = SqliteTestSupport.foundation(directory);
        AgentSessionId sessionId = new AgentSessionId("session");
        foundation
                .agentSessions()
                .insert(AgentSession.open(
                        sessionId,
                        new TenantRef("tenant"),
                        new PrincipalRef("owner", "user"),
                        null,
                        SessionScope.USER,
                        NOW,
                        Map.of()));
        SqliteSessionMessageRepository repository = foundation.messages();
        AtomicInteger notifications = new AtomicInteger();
        repository.register(source -> notifications.incrementAndGet());

        var first = repository.appendSessionMessage(new SessionMessageDraft(
                new AgentMessageId("message-1"),
                sessionId,
                Optional.empty(),
                Optional.empty(),
                MessageRole.USER,
                MessageStatus.COMPLETED,
                MessageVisibility.USER_VISIBLE,
                List.of(
                        new TextPart("hello", "plain"),
                        new AssetRefPart(new AssetRef("asset", "text/plain", "a.txt")),
                        new ArtifactRefPart(new ArtifactRef("artifact", "report", "1", "Report"), "summary"),
                        new ToolCallPart(
                                new ToolCallId("call"),
                                new ProviderToolCallCorrelationId("provider-call"),
                                "file.read",
                                "1"),
                        new ToolResultPart(
                                new ToolCallId("call"), new ProviderToolCallCorrelationId("provider-call"), "done")),
                Map.of("source", "test"),
                NOW));
        var second = repository.appendSessionMessage(new SessionMessageDraft(
                new AgentMessageId("message-2"),
                sessionId,
                Optional.empty(),
                Optional.of(first.id()),
                MessageRole.ASSISTANT,
                MessageStatus.COMPLETED,
                MessageVisibility.USER_VISIBLE,
                List.of(new TextPart("answer", "markdown")),
                Map.of(),
                NOW.plusSeconds(1)));

        assertThat(first.sequence()).isEqualTo(1);
        assertThat(second.sequence()).isEqualTo(2);
        assertThat(repository.messagesAfter(sessionId, new MessageCursor(1), 10))
                .containsExactly(second);
        assertThat(repository.recentMessages(sessionId, new MessageCursor(2), 1).messages())
                .containsExactly(second);
        assertThat(repository.latestMessageCursor(sessionId)).contains(new MessageCursor(2));
        assertThat(repository.message(first.id())).contains(first);

        assertThat(repository.redactMessage(first.id()).status()).isEqualTo(MessageStatus.REDACTED);
        assertThat(notifications).hasValue(1);
        assertThat(repository.message(first.id()).orElseThrow().contents())
                .containsExactly(new TextPart("[REDACTED]", "plain"));
    }
}
