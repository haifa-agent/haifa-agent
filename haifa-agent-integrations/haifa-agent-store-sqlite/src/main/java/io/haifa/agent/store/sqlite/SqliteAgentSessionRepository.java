package io.haifa.agent.store.sqlite;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.ProjectRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.session.AgentSession;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.session.AgentSessionPersistenceSnapshot;
import io.haifa.agent.runtime.core.storage.AgentSessionRepository;
import io.haifa.agent.runtime.core.storage.OptimisticLockException;
import io.haifa.agent.store.sqlite.codec.EncodedPayload;
import io.haifa.agent.store.sqlite.codec.VersionedPayloadCodecRegistry;
import io.haifa.agent.store.sqlite.mybatis.RuntimeStoreMapper;
import io.haifa.agent.store.sqlite.mybatis.SessionRow;
import io.haifa.agent.store.sqlite.payload.MetadataPayload;
import io.haifa.agent.store.sqlite.payload.SqliteRuntimePayloadTypes;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.apache.ibatis.exceptions.PersistenceException;

/** SQLite/MyBatis implementation of the Core session aggregate repository. */
public final class SqliteAgentSessionRepository implements AgentSessionRepository {

    private final SqliteRuntimeUnitOfWork unitOfWork;
    private final VersionedPayloadCodecRegistry codecs;

    public SqliteAgentSessionRepository(SqliteRuntimeUnitOfWork unitOfWork, VersionedPayloadCodecRegistry codecs) {
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        this.codecs = Objects.requireNonNull(codecs, "codecs must not be null");
    }

    @Override
    public void insert(AgentSession session) {
        Objects.requireNonNull(session, "session must not be null");
        execute(() -> {
            try {
                unitOfWork.mapper(RuntimeStoreMapper.class).insertSession(toRow(session));
                return null;
            } catch (PersistenceException exception) {
                throw duplicate("session already exists: " + session.id().value(), exception);
            }
        });
    }

    @Override
    public void save(AgentSession session, long expectedVersion) {
        Objects.requireNonNull(session, "session must not be null");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        execute(() -> {
            int updated = unitOfWork.mapper(RuntimeStoreMapper.class).updateSession(toRow(session), expectedVersion);
            if (updated != 1) {
                throw new OptimisticLockException(
                        "session version conflict for " + session.id().value() + " at " + expectedVersion);
            }
            return null;
        });
    }

    @Override
    public Optional<AgentSession> find(AgentSessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        return execute(() -> Optional.ofNullable(
                        unitOfWork.mapper(RuntimeStoreMapper.class).findSession(sessionId.value()))
                .map(this::fromRow));
    }

    private SessionRow toRow(AgentSession session) {
        AgentSessionPersistenceSnapshot snapshot = session.persistenceSnapshot();
        EncodedPayload metadata =
                codecs.encode(SqliteRuntimePayloadTypes.METADATA, new MetadataPayload(snapshot.metadata()));
        return new SessionRow(
                snapshot.id().value(),
                snapshot.schemaVersion(),
                snapshot.tenant().tenantId(),
                snapshot.owner().principalId(),
                snapshot.owner().principalType(),
                snapshot.project() == null ? null : snapshot.project().projectId(),
                snapshot.scope(),
                snapshot.status(),
                snapshot.createdAt(),
                snapshot.updatedAt(),
                snapshot.closedAt(),
                snapshot.version(),
                metadata.schemaVersion(),
                metadata.bytes(),
                metadata.hash());
    }

    private AgentSession fromRow(SessionRow row) {
        MetadataPayload metadata = codecs.decode(
                SqliteRuntimePayloadTypes.METADATA,
                new EncodedPayload(
                        SqliteRuntimePayloadTypes.METADATA.name(),
                        row.metadataSchemaVersion(),
                        row.metadataPayload(),
                        row.metadataHash()));
        return AgentSession.reconstitute(new AgentSessionPersistenceSnapshot(
                row.schemaVersion(),
                new AgentSessionId(row.sessionId()),
                new TenantRef(row.tenantId()),
                new PrincipalRef(row.ownerPrincipalId(), row.ownerPrincipalType()),
                row.projectId() == null ? null : new ProjectRef(row.projectId()),
                row.scope(),
                row.createdAt(),
                row.status(),
                row.updatedAt(),
                row.closedAt(),
                row.version(),
                metadata.values()));
    }

    private static IllegalStateException duplicate(String message, PersistenceException cause) {
        return new IllegalStateException(message, cause);
    }

    private <T> T execute(Supplier<T> work) {
        try {
            return unitOfWork.execute(work);
        } catch (SqliteStoreException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw exception;
        }
    }
}
