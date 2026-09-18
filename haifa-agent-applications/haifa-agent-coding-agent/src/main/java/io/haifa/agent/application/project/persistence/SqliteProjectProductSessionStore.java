package io.haifa.agent.application.project.persistence;

import io.haifa.agent.application.project.product.ProjectProductSession;
import io.haifa.agent.application.project.product.ProjectProductSessionStore;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.ProjectRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.session.AgentSession;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.project.configuration.ProjectConfigurationId;
import io.haifa.agent.project.configuration.ProjectConfigurationVersion;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.runtime.core.storage.AgentSessionRepository;
import io.haifa.agent.store.sqlite.SqliteRuntimeUnitOfWork;
import io.haifa.agent.store.sqlite.SqliteStoreException;
import io.haifa.agent.store.sqlite.SqliteStoreFailure;
import java.util.Objects;
import java.util.Optional;

/** Application-owned, versioned Project Product Session mapping stored beside Runtime SQLite facts. */
public final class SqliteProjectProductSessionStore implements ProjectProductSessionStore {
    private static final String SCHEMA_VERSION = "1";
    private final SqliteRuntimeUnitOfWork unitOfWork;
    private final AgentSessionRepository coreSessions;

    public SqliteProjectProductSessionStore(SqliteRuntimeUnitOfWork unitOfWork, AgentSessionRepository coreSessions) {
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        this.coreSessions = Objects.requireNonNull(coreSessions, "coreSessions must not be null");
    }

    @Override
    public void create(ProjectProductSession session) {
        Objects.requireNonNull(session, "session must not be null");
        validateCore(session, requireCore(session.sessionId()));
        unitOfWork.execute(() -> {
            int updated = mapper().insert(toRow(session));
            if (updated == 0
                    && find(session.sessionId()).filter(session::equals).isEmpty()) {
                throw failure("Project Product Session identity is already bound");
            }
            if (updated > 1) throw failure("Product session insert affected an unexpected row");
            return null;
        });
    }

    @Override
    public Optional<ProjectProductSession> find(AgentSessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        return unitOfWork.execute(() -> {
            ProjectProductSessionRow row = mapper().find(sessionId.value());
            if (row == null) return Optional.empty();
            ProjectProductSession session = fromRow(row);
            validateCore(session, requireCore(sessionId));
            return Optional.of(session);
        });
    }

    private ProjectProductSessionMapper mapper() {
        return unitOfWork.mapper(ProjectProductSessionMapper.class);
    }

    private static ProjectProductSessionRow toRow(ProjectProductSession session) {
        return new ProjectProductSessionRow(
                session.sessionId().value(),
                SCHEMA_VERSION,
                session.projectId().value(),
                session.workspaceId().value(),
                session.tenant().tenantId(),
                session.principal().principalId(),
                session.principal().principalType(),
                session.configurationId().value(),
                session.configurationVersion().value(),
                session.configurationDigest(),
                session.productProfileRef());
    }

    private static ProjectProductSession fromRow(ProjectProductSessionRow row) {
        if (!SCHEMA_VERSION.equals(row.schemaVersion())) {
            throw failure("Unsupported Project Product Session schema");
        }
        return new ProjectProductSession(
                new AgentSessionId(row.sessionId()),
                new ProjectId(row.projectId()),
                new WorkspaceId(row.workspaceId()),
                new TenantRef(row.tenantId()),
                new PrincipalRef(row.principalId(), row.principalType()),
                new ProjectConfigurationId(row.configurationId()),
                new ProjectConfigurationVersion(row.configurationVersion()),
                row.configurationDigest(),
                row.productProfileRef());
    }

    private AgentSession requireCore(AgentSessionId sessionId) {
        return coreSessions
                .find(sessionId)
                .orElseThrow(() -> failure("Project Product Session has no matching Core Session"));
    }

    private static void validateCore(ProjectProductSession product, AgentSession core) {
        boolean consistent = core.id().equals(product.sessionId())
                && core.tenant().equals(product.tenant())
                && core.owner().equals(product.principal())
                && core.project()
                        .map(ProjectRef::projectId)
                        .filter(product.projectId().value()::equals)
                        .isPresent();
        if (!consistent) throw failure("Core Session and Project Product Session are inconsistent");
    }

    private static SqliteStoreException failure(String message) {
        return new SqliteStoreException(SqliteStoreFailure.TRANSACTION_FAILED, message);
    }
}
