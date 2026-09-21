package io.haifa.agent.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.context.compression.CompactionQuality;
import io.haifa.agent.context.compression.ConversationSummary;
import io.haifa.agent.context.compression.SummaryId;
import io.haifa.agent.context.compression.SummaryVersion;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.message.MessageStatus;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.runtime.core.storage.OptimisticLockException;
import io.haifa.agent.runtime.core.storage.SessionMessageDraft;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteConversationSummaryAtomicityTest {

    @Test
    void compareAndSetValidAndLatestSnapshotUseTheAtomicRepositoryContract(@TempDir Path directory) {
        try (SqliteStoreFoundation foundation = SqliteTestSupport.foundation(directory)) {
            var run = SqliteAggregateTestData.prepareRun(foundation);
            AgentMessage message = foundation
                    .messages()
                    .appendSessionMessage(new SessionMessageDraft(
                            new AgentMessageId("summary-source"),
                            run.sessionId(),
                            Optional.of(run.id()),
                            Optional.empty(),
                            MessageRole.USER,
                            MessageStatus.COMPLETED,
                            MessageVisibility.USER_VISIBLE,
                            List.of(new TextPart("source", "plain")),
                            Map.of(),
                            SqliteAggregateTestData.NOW));
            ConversationSummary summary = summary(message, 1);

            foundation.summaries().compareAndSetValid(summary, 0);

            var snapshot = foundation.summaries().latestSnapshot(run.sessionId());
            assertThat(snapshot.latestVersion()).isEqualTo(1L);
            assertThat(snapshot.latestValid()).contains(summary);

            foundation.messages().redactMessage(message.id());
            var redactedSnapshot = foundation.summaries().latestSnapshot(run.sessionId());
            assertThat(redactedSnapshot.latestVersion()).isEqualTo(1L);
            assertThat(redactedSnapshot.latestValid()).isEmpty();
        }
    }

    @Test
    void compareAndSetValidRejectsRedactedSourceWithoutPersistingAVersion(@TempDir Path directory) {
        try (SqliteStoreFoundation foundation = SqliteTestSupport.foundation(directory)) {
            var run = SqliteAggregateTestData.prepareRun(foundation);
            AgentMessage message = foundation
                    .messages()
                    .appendSessionMessage(new SessionMessageDraft(
                            new AgentMessageId("redacted-summary-source"),
                            run.sessionId(),
                            Optional.of(run.id()),
                            Optional.empty(),
                            MessageRole.USER,
                            MessageStatus.COMPLETED,
                            MessageVisibility.USER_VISIBLE,
                            List.of(new TextPart("source", "plain")),
                            Map.of(),
                            SqliteAggregateTestData.NOW));
            foundation.messages().redactMessage(message.id());

            assertThatThrownBy(() -> foundation.summaries().compareAndSetValid(summary(message, 1), 0))
                    .isInstanceOf(OptimisticLockException.class)
                    .hasMessageContaining("redacted");
            assertThat(foundation.summaries().latestSnapshot(run.sessionId()).latestVersion())
                    .isZero();
        }
    }

    @Test
    void versionThreeKeepsDirectSourcesBoundedWhileValidatingTheWholeCoverageRange(@TempDir Path directory) {
        try (SqliteStoreFoundation foundation = SqliteTestSupport.foundation(directory)) {
            var run = SqliteAggregateTestData.prepareRun(foundation);
            AgentMessage first = append(foundation, run.sessionId(), run.id(), "bounded-source-1");
            append(foundation, run.sessionId(), run.id(), "bounded-source-2");
            AgentMessage third = append(foundation, run.sessionId(), run.id(), "bounded-source-3");
            ConversationSummary summary = new ConversationSummary(
                    new SummaryId("bounded-summary"),
                    new SummaryVersion(1),
                    run.sessionId(),
                    first.cursor(),
                    third.cursor(),
                    List.of(third.id()),
                    3,
                    "sha256:rolling",
                    List.of("fact"),
                    List.of(),
                    List.of(),
                    List.of(),
                    10,
                    SqliteAggregateTestData.NOW,
                    "policy-1",
                    "compressor-1",
                    Set.of("internal"),
                    true,
                    Optional.empty(),
                    CompactionQuality.DETERMINISTIC_DEGRADED);

            foundation.summaries().compareAndSetValid(summary, 0);

            ConversationSummary reloaded =
                    foundation.summaries().latestValid(run.sessionId()).orElseThrow();
            assertThat(reloaded.sourceMessageIds()).containsExactly(third.id());
            assertThat(reloaded.coveredSourceCount()).isEqualTo(3);
            assertThat(reloaded.sourceHash()).isEqualTo("sha256:rolling");
        }
    }

    private static AgentMessage append(
            SqliteStoreFoundation foundation,
            io.haifa.agent.core.session.AgentSessionId sessionId,
            io.haifa.agent.core.run.AgentRunId runId,
            String id) {
        return foundation
                .messages()
                .appendSessionMessage(new SessionMessageDraft(
                        new AgentMessageId(id),
                        sessionId,
                        Optional.of(runId),
                        Optional.empty(),
                        MessageRole.USER,
                        MessageStatus.COMPLETED,
                        MessageVisibility.USER_VISIBLE,
                        List.of(new TextPart(id, "plain")),
                        Map.of(),
                        SqliteAggregateTestData.NOW));
    }

    private static ConversationSummary summary(AgentMessage source, long version) {
        return new ConversationSummary(
                new SummaryId("summary-" + version),
                new SummaryVersion(version),
                source.sessionId(),
                source.cursor(),
                source.cursor(),
                List.of(source.id()),
                "sha256:source",
                List.of("fact"),
                List.of(),
                List.of(),
                List.of(),
                10,
                SqliteAggregateTestData.NOW,
                "policy-1",
                "compressor-1",
                Set.of("internal"),
                true,
                Optional.empty(),
                CompactionQuality.DETERMINISTIC_DEGRADED);
    }
}
