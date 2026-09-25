package io.haifa.agent.runtime.core.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.context.compression.CompactionQuality;
import io.haifa.agent.context.compression.CompressionPolicy;
import io.haifa.agent.context.compression.ConversationSummary;
import io.haifa.agent.context.compression.DeterministicContextCompressor;
import io.haifa.agent.context.compression.SummaryId;
import io.haifa.agent.context.compression.SummaryVersion;
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
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import io.haifa.agent.runtime.core.storage.SessionMessageDraft;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ActiveContextSnapshotsTest {
    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");
    private static final AgentSessionId SESSION = new AgentSessionId("active-context-session");

    @Test
    void followsTheCursorIncrementallyAndReadsOnlyThePostSummaryWindowAfterRebuild() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        CompressionPolicy policy = new CompressionPolicy(12, 32, 4);
        DeterministicContextCompressor compressor = new DeterministicContextCompressor();
        ActiveContextSnapshots snapshots = new ActiveContextSnapshots(store, store, policy, compressor);

        List<AgentMessage> messages = appendTurns(store, 3, 0);
        ActiveContextSnapshots.Access initial = snapshots.current(SESSION);
        assertThat(initial.status()).isEqualTo(ActiveContextSnapshots.AccessStatus.READY);
        assertThat(initial.metrics().rebuildReason()).isEqualTo(ActiveContextSnapshots.RebuildReason.INITIAL);
        assertThat(initial.metrics().historyRowsRead()).isEqualTo(6);
        assertThat(initial.snapshot().activeMessages()).hasSize(6);

        ActiveContextSnapshots.Access hit = snapshots.current(SESSION);
        assertThat(hit.metrics().snapshotHit()).isTrue();
        assertThat(hit.metrics().historyRowsRead()).isZero();
        assertThat(hit.metrics().atomicGroupCandidateScans()).isZero();

        appendTurns(store, 1, 3);
        ActiveContextSnapshots.Access delta = snapshots.current(SESSION);
        assertThat(delta.metrics().rebuildReason()).isEqualTo(ActiveContextSnapshots.RebuildReason.DELTA);
        assertThat(delta.metrics().historyRowsRead()).isEqualTo(2);
        assertThat(delta.metrics().snapshotDeltaRows()).isEqualTo(2);
        assertThat(delta.snapshot().activeMessages()).hasSize(8);

        ConversationSummary summary = new ConversationSummary(
                new SummaryId("summary-1"),
                new SummaryVersion(1),
                SESSION,
                messages.getFirst().cursor(),
                messages.getLast().cursor(),
                List.of(
                        messages.get(messages.size() - 2).id(),
                        messages.getLast().id()),
                messages.size(),
                "sha256:bounded-provenance",
                List.of("earlier turns"),
                List.of(),
                List.of(),
                List.of(),
                8,
                NOW,
                policy.version(),
                compressor.version(),
                Set.of("internal"),
                true,
                Optional.empty(),
                CompactionQuality.DETERMINISTIC_DEGRADED);
        store.compareAndSetValid(summary, 0);
        snapshots.invalidate(SESSION, ActiveContextSnapshots.RebuildReason.SUMMARY_CHANGED);

        ActiveContextSnapshots.Access afterSummary = snapshots.current(SESSION);
        assertThat(afterSummary.metrics().historyRowsRead()).isEqualTo(2);
        assertThat(afterSummary.snapshot().summary()).contains(summary);
        assertThat(afterSummary.snapshot().activeMessages()).hasSize(2);
        assertThat(afterSummary.snapshot().selectedMessages()).hasSize(2);
    }

    @Test
    void currentReturnsNeedsCompactionInsteadOfThrowingWhenHistoryExceedsTheActiveWindow() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        CompressionPolicy policy = new CompressionPolicy(12, 32, 4);
        DeterministicContextCompressor compressor = new DeterministicContextCompressor();
        AgentSessionId session = new AgentSessionId("needs-compaction-session");
        for (int index = 0; index < 4_097; index++) {
            append(store, session, "needs-compaction-" + index, index);
        }

        ActiveContextSnapshots.Access access =
                new ActiveContextSnapshots(store, store, policy, compressor).current(session);

        assertThat(access.status()).isEqualTo(ActiveContextSnapshots.AccessStatus.NEEDS_COMPACTION);
        assertThat(access.snapshot().hasMoreHistory()).isTrue();
        assertThat(access.snapshot().activeMessages()).hasSize(4_096);
        assertThat(access.metrics().historyRowsRead()).isEqualTo(4_097);
    }

    @Test
    void fixedActiveWindowReadsStayConstantAcrossOneThousandTenThousandAndOneHundredThousandRows() {
        for (int historySize : List.of(1_000, 10_000, 100_000)) {
            InMemoryRuntimeStore store = new InMemoryRuntimeStore();
            CompressionPolicy policy = new CompressionPolicy(12, 32, 4);
            DeterministicContextCompressor compressor = new DeterministicContextCompressor();
            AgentSessionId session = new AgentSessionId("structural-" + historySize);
            List<AgentMessage> boundarySources = new ArrayList<>();
            int covered = historySize - 10;
            for (int index = 0; index < historySize; index++) {
                AgentMessage message = append(store, session, "h-" + historySize + "-" + index, index);
                if (index == covered - 2 || index == covered - 1) boundarySources.add(message);
            }
            ConversationSummary summary = new ConversationSummary(
                    new SummaryId("structural-summary-" + historySize),
                    new SummaryVersion(1),
                    session,
                    new io.haifa.agent.core.message.MessageCursor(1),
                    new io.haifa.agent.core.message.MessageCursor(covered),
                    boundarySources.stream().map(AgentMessage::id).toList(),
                    covered,
                    "sha256:structural-" + historySize,
                    List.of("bounded history"),
                    List.of(),
                    List.of(),
                    List.of(),
                    8,
                    NOW,
                    policy.version(),
                    compressor.version(),
                    Set.of("internal"),
                    true,
                    Optional.empty(),
                    CompactionQuality.DETERMINISTIC_DEGRADED);
            store.compareAndSetValid(summary, 0);

            ActiveContextSnapshots.Access access =
                    new ActiveContextSnapshots(store, store, policy, compressor).current(session);

            assertThat(access.metrics().historyRowsRead())
                    .as("history=%s", historySize)
                    .isEqualTo(10);
            assertThat(access.metrics().activeRowsSelected())
                    .as("history=%s", historySize)
                    .isEqualTo(10);
            assertThat(access.metrics().toolCallBatchCount()).isZero();
            assertThat(access.metrics().continuationBatchCount()).isZero();
            assertThat(access.snapshot().activeMessages()).hasSize(10);
            assertThat(access.snapshot().characterCount()).isLessThan(1_000);
            assertThat(access.snapshot().payloadBytes()).isLessThan(1_000);
        }
    }

    @Test
    void boundedPageStopsBeforeAToolProtocolGroupThatCrossesThePageBoundary() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        CompressionPolicy policy = new CompressionPolicy(12, 32, 4);
        DeterministicContextCompressor compressor = new DeterministicContextCompressor();
        AgentSessionId session = new AgentSessionId("cross-page-tool-session");
        for (int index = 0; index < 4_095; index++) {
            append(store, session, "prefix-" + index, index);
        }
        ToolCallId callId = new ToolCallId("cross-page-call");
        ProviderToolCallCorrelationId correlation = new ProviderToolCallCorrelationId("cross-page-provider");
        store.appendSessionMessage(new SessionMessageDraft(
                new AgentMessageId("cross-page-call-message"),
                session,
                Optional.empty(),
                Optional.empty(),
                MessageRole.ASSISTANT,
                MessageStatus.COMPLETED,
                MessageVisibility.AGENT_VISIBLE,
                List.of(new ToolCallPart(callId, correlation, "read_file", "1.0")),
                Map.of(),
                NOW));
        store.appendSessionMessage(new SessionMessageDraft(
                new AgentMessageId("cross-page-result-message"),
                session,
                Optional.empty(),
                Optional.empty(),
                MessageRole.TOOL,
                MessageStatus.COMPLETED,
                MessageVisibility.AGENT_VISIBLE,
                List.of(new ToolResultPart(callId, correlation, "done")),
                Map.of(),
                NOW));

        ActiveContextSnapshots.Access access =
                new ActiveContextSnapshots(store, store, policy, compressor).currentForCompaction(session);

        assertThat(access.snapshot().hasMoreHistory()).isTrue();
        assertThat(access.snapshot().storeThrough().value()).isEqualTo(4_095);
        assertThat(access.snapshot().activeMessages()).hasSize(4_095);
        assertThat(access.snapshot().selectedMessages())
                .extracting(message -> message.id().value())
                .doesNotContain("cross-page-call-message", "cross-page-result-message");
    }

    private static List<AgentMessage> appendTurns(InMemoryRuntimeStore store, int turns, int offset) {
        List<AgentMessage> appended = new ArrayList<>();
        for (int index = 0; index < turns; index++) {
            int turn = offset + index;
            appended.add(append(store, "user-" + turn, MessageRole.USER, "question " + turn));
            appended.add(append(store, "assistant-" + turn, MessageRole.ASSISTANT, "answer " + turn));
        }
        return appended;
    }

    private static AgentMessage append(InMemoryRuntimeStore store, String id, MessageRole role, String text) {
        return append(store, SESSION, id, role, text);
    }

    private static AgentMessage append(InMemoryRuntimeStore store, AgentSessionId session, String id, int index) {
        MessageRole role = index % 2 == 0 ? MessageRole.USER : MessageRole.ASSISTANT;
        return append(store, session, id, role, "m" + index);
    }

    private static AgentMessage append(
            InMemoryRuntimeStore store, AgentSessionId session, String id, MessageRole role, String text) {
        return store.appendSessionMessage(new SessionMessageDraft(
                new AgentMessageId(id),
                session,
                Optional.empty(),
                Optional.empty(),
                role,
                MessageStatus.COMPLETED,
                role == MessageRole.USER ? MessageVisibility.USER_VISIBLE : MessageVisibility.AGENT_VISIBLE,
                List.of(new TextPart(text, "plain")),
                Map.of(),
                NOW));
    }
}
