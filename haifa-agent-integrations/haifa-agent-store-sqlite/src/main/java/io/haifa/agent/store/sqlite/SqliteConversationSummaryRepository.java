package io.haifa.agent.store.sqlite;

import io.haifa.agent.context.compression.CompactionQuality;
import io.haifa.agent.context.compression.ConversationSummary;
import io.haifa.agent.context.compression.ConversationSummaryRepository;
import io.haifa.agent.context.compression.SummaryId;
import io.haifa.agent.context.compression.SummaryVersion;
import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.message.MessageCursor;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.runtime.core.storage.OptimisticLockException;
import io.haifa.agent.store.sqlite.codec.EncodedPayload;
import io.haifa.agent.store.sqlite.codec.VersionedPayloadCodecRegistry;
import io.haifa.agent.store.sqlite.mybatis.ConversationSummaryRow;
import io.haifa.agent.store.sqlite.mybatis.RuntimeStoreMapper;
import io.haifa.agent.store.sqlite.payload.ConversationSummaryPayload;
import io.haifa.agent.store.sqlite.payload.ConversationSummaryPayloadV2;
import io.haifa.agent.store.sqlite.payload.SqliteRuntimePayloadTypes;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class SqliteConversationSummaryRepository implements ConversationSummaryRepository {
    private final SqliteRuntimeUnitOfWork unitOfWork;
    private final VersionedPayloadCodecRegistry codecs;

    public SqliteConversationSummaryRepository(
            SqliteRuntimeUnitOfWork unitOfWork, VersionedPayloadCodecRegistry codecs) {
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        this.codecs = Objects.requireNonNull(codecs, "codecs must not be null");
    }

    @Override
    public Optional<ConversationSummary> latestValid(AgentSessionId sessionId) {
        return execute(() -> Optional.ofNullable(
                        unitOfWork.mapper(RuntimeStoreMapper.class).latestValidSummary(sessionId.value()))
                .map(this::fromRow));
    }

    @Override
    public Optional<ConversationSummary> find(SummaryId id, SummaryVersion version) {
        return execute(() -> Optional.ofNullable(
                        unitOfWork.mapper(RuntimeStoreMapper.class).findSummary(id.value(), version.value()))
                .map(this::fromRow));
    }

    @Override
    public long latestVersion(AgentSessionId sessionId) {
        return execute(() -> unitOfWork.mapper(RuntimeStoreMapper.class).latestSummaryVersion(sessionId.value()));
    }

    @Override
    public ConversationSummary compareAndSet(ConversationSummary summary, long expectedPreviousVersion) {
        Objects.requireNonNull(summary, "summary must not be null");
        return execute(() -> {
            RuntimeStoreMapper mapper = unitOfWork.mapper(RuntimeStoreMapper.class);
            long actual = mapper.latestSummaryVersion(summary.sessionId().value());
            if (actual != expectedPreviousVersion || summary.version().value() != expectedPreviousVersion + 1) {
                throw new OptimisticLockException(
                        "summary version conflict: expected " + expectedPreviousVersion + " but was " + actual);
            }
            EncodedPayload content;
            if (summary.semanticSummary().isPresent()) {
                content = codecs.encode(
                        SqliteRuntimePayloadTypes.CONVERSATION_SUMMARY_V2,
                        ConversationSummaryPayloadV2.from(summary));
            } else {
                content = codecs.encode(
                        SqliteRuntimePayloadTypes.CONVERSATION_SUMMARY,
                        ConversationSummaryPayload.from(summary));
            }
            mapper.insertSummary(new ConversationSummaryRow(
                    summary.id().value(),
                    summary.version().value(),
                    summary.sessionId().value(),
                    summary.coveredFrom().value(),
                    summary.coveredThrough().value(),
                    summary.sourceHash(),
                    content.schemaVersion(),
                    content.bytes(),
                    content.hash(),
                    summary.estimatedTokens(),
                    summary.policyVersion(),
                    summary.compressorVersion(),
                    summary.valid(),
                    summary.createdAt()));
            return summary;
        });
    }

    @Override
    public void invalidateContaining(AgentSessionId sessionId, AgentMessageId messageId) {
        execute(() -> {
            // Source ids live inside the integrity-protected payload, so conservatively invalidate
            // all derived summaries for this session.
            unitOfWork
                    .mapper(RuntimeStoreMapper.class)
                    .invalidateSummaryContaining(sessionId.value(), messageId.value());
            return null;
        });
    }

    @Override
    public boolean coversValidSource(ConversationSummary summary, MessageCursor through) {
        if (!summary.valid() || summary.coveredThrough().compareTo(through) < 0) return false;
        return execute(() -> unitOfWork
                        .mapper(RuntimeStoreMapper.class)
                        .validSummarySourceCount(
                                summary.sessionId().value(),
                                summary.sourceMessageIds().stream()
                                         .map(AgentMessageId::value)
                                         .toList())
                == summary.sourceMessageIds().size());
    }

    private ConversationSummary fromRow(ConversationSummaryRow row) {
        if ("2".equals(row.contentSchemaVersion())) {
            ConversationSummaryPayloadV2 content = codecs.decode(
                    SqliteRuntimePayloadTypes.CONVERSATION_SUMMARY_V2,
                    new EncodedPayload(
                            SqliteRuntimePayloadTypes.CONVERSATION_SUMMARY_V2.name(),
                            row.contentSchemaVersion(),
                            row.contentPayload(),
                            row.contentHash()));
            return new ConversationSummary(
                    new SummaryId(row.summaryId()),
                    new SummaryVersion(row.summaryVersion()),
                    new AgentSessionId(row.sessionId()),
                    new MessageCursor(row.coveredFrom()),
                    new MessageCursor(row.coveredThrough()),
                    content.sourceMessageIds().stream().map(AgentMessageId::new).toList(),
                    row.sourceHash(),
                    content.facts(),
                    content.decisions(),
                    content.openItems(),
                    content.toolOutcomeReferences().stream().map(ToolCallId::new).toList(),
                    row.estimatedTokens(),
                    row.createdAt(),
                    row.policyVersion(),
                    row.compressorVersion(),
                    content.securityLabels(),
                    row.valid(),
                    content.semanticSummary(),
                    content.quality() != null
                            ? CompactionQuality.valueOf(content.quality())
                            : CompactionQuality.DETERMINISTIC_DEGRADED);
        }
        ConversationSummaryPayload content = codecs.decode(
                SqliteRuntimePayloadTypes.CONVERSATION_SUMMARY,
                new EncodedPayload(
                        SqliteRuntimePayloadTypes.CONVERSATION_SUMMARY.name(),
                        row.contentSchemaVersion(),
                        row.contentPayload(),
                        row.contentHash()));
        return new ConversationSummary(
                new SummaryId(row.summaryId()),
                new SummaryVersion(row.summaryVersion()),
                new AgentSessionId(row.sessionId()),
                new MessageCursor(row.coveredFrom()),
                new MessageCursor(row.coveredThrough()),
                content.sourceMessageIds().stream().map(AgentMessageId::new).toList(),
                row.sourceHash(),
                content.facts(),
                content.decisions(),
                content.openItems(),
                content.toolOutcomeReferences().stream().map(ToolCallId::new).toList(),
                row.estimatedTokens(),
                row.createdAt(),
                row.policyVersion(),
                row.compressorVersion(),
                content.securityLabels(),
                row.valid());
    }

    private <T> T execute(Supplier<T> work) {
        try {
            return unitOfWork.execute(work);
        } catch (SqliteStoreException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) throw runtimeException;
            throw exception;
        }
    }
}
