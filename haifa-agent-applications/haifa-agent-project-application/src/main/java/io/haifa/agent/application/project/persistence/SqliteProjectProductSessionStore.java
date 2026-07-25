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
import io.haifa.agent.store.sqlite.SqliteConnectionFactory;
import io.haifa.agent.store.sqlite.SqliteStoreException;
import io.haifa.agent.store.sqlite.SqliteStoreFailure;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/** Application-owned, versioned Project Product Session mapping stored beside Runtime SQLite facts. */
public final class SqliteProjectProductSessionStore implements ProjectProductSessionStore {
    private static final String SCHEMA_VERSION = "1";
    private final SqliteConnectionFactory connections;
    private final AgentSessionRepository coreSessions;

    public SqliteProjectProductSessionStore(SqliteConnectionFactory connections, AgentSessionRepository coreSessions) {
        this.connections = Objects.requireNonNull(connections, "connections must not be null");
        this.coreSessions = Objects.requireNonNull(coreSessions, "coreSessions must not be null");
    }

    @Override
    public void create(ProjectProductSession session) {
        Objects.requireNonNull(session, "session must not be null");
        validateCore(session, requireCore(session.sessionId()));
        String sql =
                """
                INSERT INTO project_product_session(
                    session_id, schema_version, project_id, workspace_id, tenant_id,
                    principal_id, principal_type, configuration_id, configuration_version,
                    configuration_digest, product_profile_ref
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (var connection = connections.openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, session.sessionId().value());
            statement.setString(2, SCHEMA_VERSION);
            statement.setString(3, session.projectId().value());
            statement.setString(4, session.workspaceId().value());
            statement.setString(5, session.tenant().tenantId());
            statement.setString(6, session.principal().principalId());
            statement.setString(7, session.principal().principalType());
            statement.setString(8, session.configurationId().value());
            statement.setString(9, session.configurationVersion().value());
            statement.setString(10, session.configurationDigest());
            statement.setString(11, session.productProfileRef());
            if (statement.executeUpdate() != 1) throw failure("Product session insert affected an unexpected row");
        } catch (SQLException exception) {
            throw failure("Unable to create Project Product Session", exception);
        }
    }

    @Override
    public Optional<ProjectProductSession> find(AgentSessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        String sql =
                """
                SELECT schema_version, project_id, workspace_id, tenant_id, principal_id, principal_type,
                       configuration_id, configuration_version, configuration_digest, product_profile_ref
                  FROM project_product_session
                 WHERE session_id = ?
                """;
        try (var connection = connections.openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId.value());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                if (!SCHEMA_VERSION.equals(result.getString("schema_version"))) {
                    throw failure("Unsupported Project Product Session schema");
                }
                ProjectProductSession session = new ProjectProductSession(
                        sessionId,
                        new ProjectId(result.getString("project_id")),
                        new WorkspaceId(result.getString("workspace_id")),
                        new TenantRef(result.getString("tenant_id")),
                        new PrincipalRef(result.getString("principal_id"), result.getString("principal_type")),
                        new ProjectConfigurationId(result.getString("configuration_id")),
                        new ProjectConfigurationVersion(result.getString("configuration_version")),
                        result.getString("configuration_digest"),
                        result.getString("product_profile_ref"));
                validateCore(session, requireCore(sessionId));
                return Optional.of(session);
            }
        } catch (SQLException exception) {
            throw failure("Unable to read Project Product Session", exception);
        }
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

    private static SqliteStoreException failure(String message, Throwable cause) {
        return new SqliteStoreException(SqliteStoreFailure.TRANSACTION_FAILED, message, cause);
    }
}
