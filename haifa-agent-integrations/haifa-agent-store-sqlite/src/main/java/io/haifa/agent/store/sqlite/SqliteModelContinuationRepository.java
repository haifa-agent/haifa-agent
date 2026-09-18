package io.haifa.agent.store.sqlite;

import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.model.api.SensitiveModelReasoning;
import io.haifa.agent.runtime.core.model.continuation.ModelContinuationDraft;
import io.haifa.agent.runtime.core.model.continuation.ModelContinuationException;
import io.haifa.agent.runtime.core.model.continuation.ModelContinuationFailure;
import io.haifa.agent.runtime.core.model.continuation.ModelContinuationProtector;
import io.haifa.agent.runtime.core.model.continuation.ModelContinuationRecord;
import io.haifa.agent.runtime.core.model.continuation.ModelContinuationRef;
import io.haifa.agent.runtime.core.model.continuation.ModelContinuationRepository;
import io.haifa.agent.runtime.core.model.continuation.ProtectedModelReasoning;
import io.haifa.agent.runtime.core.model.continuation.ProtectedModelReasoningEnvelope;
import io.haifa.agent.runtime.core.storage.SessionMessageDraft;
import io.haifa.agent.store.sqlite.codec.EncodedPayload;
import io.haifa.agent.store.sqlite.codec.VersionedPayloadCodecRegistry;
import io.haifa.agent.store.sqlite.mybatis.ModelContinuationRow;
import io.haifa.agent.store.sqlite.mybatis.RuntimeStoreMapper;
import io.haifa.agent.store.sqlite.payload.BinaryPayload;
import io.haifa.agent.store.sqlite.payload.SqliteRuntimePayloadTypes;
import io.haifa.agent.store.sqlite.payload.StringSetPayload;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

public final class SqliteModelContinuationRepository implements ModelContinuationRepository {
    private static final String CONTINUATION_VERSION = "1.0";
    private static final String PROTECTION_VERSION = "protector-v1";

    private final SqliteRuntimeUnitOfWork unitOfWork;
    private final VersionedPayloadCodecRegistry codecs;
    private final SqliteSessionMessageRepository messages;
    private final ModelContinuationProtector protector;

    public SqliteModelContinuationRepository(
            SqliteRuntimeUnitOfWork unitOfWork,
            VersionedPayloadCodecRegistry codecs,
            SqliteSessionMessageRepository messages,
            ModelContinuationProtector protector) {
        this.unitOfWork = Objects.requireNonNull(unitOfWork);
        this.codecs = Objects.requireNonNull(codecs);
        this.messages = Objects.requireNonNull(messages);
        this.protector = Objects.requireNonNull(protector);
        if (!protector.supportsPersistentStorage()) {
            throw new IllegalArgumentException("ephemeral continuation protectors cannot back persistent storage");
        }
    }

    @Override
    public AgentMessage appendSessionMessageWithContinuation(
            SessionMessageDraft message, ModelContinuationDraft draft) {
        if (message.role() != MessageRole.ASSISTANT
                || message.runId().isEmpty()
                || !message.runId().orElseThrow().equals(draft.runId())
                || !message.sessionId().equals(draft.sessionId())) {
            throw new IllegalArgumentException("continuation does not belong to assistant message");
        }
        String binding = continuationBinding(draft);
        ProtectedModelReasoning protectedReasoning = protector.protect(draft.reasoning(), binding);
        ProtectedModelReasoningEnvelope envelope = protectedReasoning.persistenceEnvelope();
        EncodedPayload correlations =
                codecs.encode(SqliteRuntimePayloadTypes.STRING_SET, new StringSetPayload(draft.toolCorrelationIds()));
        EncodedPayload nonce = codecs.encode(SqliteRuntimePayloadTypes.BINARY, new BinaryPayload(envelope.nonce()));
        EncodedPayload ciphertext =
                codecs.encode(SqliteRuntimePayloadTypes.BINARY, new BinaryPayload(envelope.ciphertext()));
        return execute(() -> {
            AgentMessage appended = messages.appendSessionMessage(message);
            unitOfWork
                    .mapper(RuntimeStoreMapper.class)
                    .insertModelContinuation(new ModelContinuationRow(
                            draft.reference().id(),
                            draft.reference().version(),
                            draft.reference().digest(),
                            draft.reference().byteLength(),
                            message.id().value(),
                            draft.runId().value(),
                            draft.sessionId().value(),
                            draft.modelCallId(),
                            draft.providerId(),
                            draft.modelId(),
                            draft.configurationDigest(),
                            correlations.schemaVersion(),
                            correlations.bytes(),
                            correlations.hash(),
                            PROTECTION_VERSION,
                            nonce.schemaVersion(),
                            nonce.bytes(),
                            nonce.hash(),
                            ciphertext.schemaVersion(),
                            ciphertext.bytes(),
                            ciphertext.hash(),
                            draft.createdAt()));
            return appended;
        });
    }

    @Override
    public Optional<ModelContinuationRecord> continuationForMessage(AgentMessageId messageId) {
        return execute(() -> Optional.ofNullable(
                        unitOfWork.mapper(RuntimeStoreMapper.class).continuationForMessage(messageId.value()))
                .map(this::fromRow));
    }

    @Override
    public List<ModelContinuationRecord> modelContinuations(AgentRunId runId) {
        return execute(() -> unitOfWork.mapper(RuntimeStoreMapper.class).modelContinuations(runId.value()).stream()
                .map(this::fromRow)
                .toList());
    }

    @Override
    public SensitiveModelReasoning resolveContinuation(
            AgentMessageId messageId, ResolvedModelSnapshot model, Set<String> toolCorrelationIds) {
        ModelContinuationRecord record = continuationForMessage(messageId)
                .orElseThrow(() -> new ModelContinuationException(
                        ModelContinuationFailure.MISSING, "required model continuation is unavailable"));
        if (!CONTINUATION_VERSION.equals(record.reference().version())) {
            throw new ModelContinuationException(
                    ModelContinuationFailure.VERSION_UNSUPPORTED, "model continuation version is unsupported");
        }
        if (!record.providerId().equals(model.providerId().value())
                || !record.modelId().equals(model.providerModelId())
                || !record.configurationDigest().equals(model.configurationDigest())
                || !record.toolCorrelationIds().equals(Set.copyOf(toolCorrelationIds))) {
            throw new ModelContinuationException(
                    ModelContinuationFailure.BINDING_MISMATCH, "model continuation binding does not match request");
        }
        SensitiveModelReasoning reasoning = protector.reveal(record.protectedReasoning(), continuationBinding(record));
        if (!reasoning.digest().equals(record.reference().digest())
                || reasoning.byteLength() != record.reference().byteLength()) {
            throw new ModelContinuationException(
                    ModelContinuationFailure.CORRUPT, "model continuation digest does not match payload");
        }
        return reasoning;
    }

    private ModelContinuationRecord fromRow(ModelContinuationRow row) {
        if (!PROTECTION_VERSION.equals(row.protectionVersion())) {
            throw new ModelContinuationException(
                    ModelContinuationFailure.VERSION_UNSUPPORTED, "protection version is unsupported");
        }
        Set<String> correlations = codecs.decode(
                        SqliteRuntimePayloadTypes.STRING_SET,
                        new EncodedPayload(
                                SqliteRuntimePayloadTypes.STRING_SET.name(),
                                row.toolCorrelationsSchemaVersion(),
                                row.toolCorrelationsPayload(),
                                row.toolCorrelationsHash()))
                .values();
        byte[] nonce = codecs.decode(
                        SqliteRuntimePayloadTypes.BINARY,
                        new EncodedPayload(
                                SqliteRuntimePayloadTypes.BINARY.name(),
                                row.nonceSchemaVersion(),
                                row.noncePayload(),
                                row.nonceHash()))
                .bytes();
        byte[] ciphertext = codecs.decode(
                        SqliteRuntimePayloadTypes.BINARY,
                        new EncodedPayload(
                                SqliteRuntimePayloadTypes.BINARY.name(),
                                row.ciphertextSchemaVersion(),
                                row.ciphertextPayload(),
                                row.ciphertextHash()))
                .bytes();
        return new ModelContinuationRecord(
                new ModelContinuationRef(
                        row.continuationId(), row.continuationVersion(), row.continuationDigest(), row.byteLength()),
                new AgentMessageId(row.assistantMessageId()),
                new AgentRunId(row.runId()),
                new AgentSessionId(row.sessionId()),
                row.modelCallId(),
                row.providerId(),
                row.modelId(),
                row.configurationDigest(),
                correlations,
                ProtectedModelReasoning.fromPersistenceEnvelope(new ProtectedModelReasoningEnvelope(nonce, ciphertext)),
                row.createdAt());
    }

    private static String continuationBinding(ModelContinuationDraft draft) {
        return String.join(
                "|",
                draft.reference().id(),
                draft.runId().value(),
                draft.sessionId().value(),
                draft.modelCallId(),
                draft.providerId(),
                draft.modelId(),
                draft.configurationDigest(),
                draft.toolCorrelationIds().stream().sorted().toList().toString());
    }

    private static String continuationBinding(ModelContinuationRecord record) {
        return String.join(
                "|",
                record.reference().id(),
                record.runId().value(),
                record.sessionId().value(),
                record.modelCallId(),
                record.providerId(),
                record.modelId(),
                record.configurationDigest(),
                record.toolCorrelationIds().stream().sorted().toList().toString());
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
