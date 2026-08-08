package io.haifa.agent.store.sqlite;

import io.haifa.agent.artifact.Artifact;
import io.haifa.agent.artifact.ArtifactId;
import io.haifa.agent.artifact.ArtifactPayloadRef;
import io.haifa.agent.artifact.ArtifactProvenance;
import io.haifa.agent.artifact.ArtifactStatus;
import io.haifa.agent.artifact.ArtifactStore;
import io.haifa.agent.artifact.ArtifactType;
import io.haifa.agent.artifact.ArtifactVersion;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.ProjectRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Public Artifact metadata adapter backed by the Runtime SQLite database. */
public final class SqliteArtifactStore implements ArtifactStore {
    private final SqliteConnectionFactory connections;

    public SqliteArtifactStore(SqliteConnectionFactory connections) {
        this.connections = java.util.Objects.requireNonNull(connections);
    }

    @Override
    public void create(Artifact artifact) {
        try (var connection = connections.openConnection();
                var statement = connection.prepareStatement(
                        """
                INSERT INTO artifact(artifact_id,artifact_version,artifact_type,title,payload_id,payload_sha256,
                  payload_byte_count,payload_media_type,project_id,workspace_ref,run_id,session_id,
                  file_change_set_ref,execution_ref,source_logical_path,source_hash,export_policy,
                  created_by_id,created_by_type,status,created_at_ms)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            var provenance = artifact.provenance();
            var payload = artifact.payload();
            statement.setString(1, artifact.id().value());
            statement.setLong(2, artifact.version().value());
            statement.setString(3, artifact.type().value());
            statement.setString(4, artifact.title());
            statement.setString(5, payload.payloadId());
            statement.setString(6, payload.sha256());
            statement.setLong(7, payload.byteCount());
            statement.setString(8, payload.mediaType());
            statement.setString(9, provenance.project().projectId());
            statement.setString(10, provenance.workspaceRef());
            statement.setString(11, provenance.runId().value());
            statement.setString(12, provenance.sessionId().value());
            statement.setString(13, provenance.fileChangeSetRef());
            statement.setString(14, provenance.executionRef());
            statement.setString(15, provenance.sourceLogicalPath());
            statement.setString(16, provenance.sourceHash());
            statement.setString(17, provenance.exportPolicy());
            statement.setString(18, provenance.createdBy().principalId());
            statement.setString(19, provenance.createdBy().principalType());
            statement.setString(20, artifact.status().name());
            statement.setLong(21, artifact.createdAt().toEpochMilli());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new SqliteStoreException(
                    SqliteStoreFailure.TRANSACTION_FAILED, "Artifact metadata write failed", exception);
        }
    }

    @Override
    public Optional<Artifact> find(ArtifactId id, ArtifactVersion version) {
        try (var connection = connections.openConnection();
                var statement = connection.prepareStatement(
                        "SELECT * FROM artifact WHERE artifact_id=? AND artifact_version=?")) {
            statement.setString(1, id.value());
            statement.setLong(2, version.value());
            try (var result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new SqliteStoreException(
                    SqliteStoreFailure.TRANSACTION_FAILED, "Artifact metadata read failed", exception);
        }
    }

    @Override
    public List<Artifact> findByProject(String projectId) {
        try (var connection = connections.openConnection();
                var statement = connection.prepareStatement(
                        "SELECT * FROM artifact WHERE project_id=? ORDER BY created_at_ms,artifact_id")) {
            statement.setString(1, projectId);
            try (var result = statement.executeQuery()) {
                List<Artifact> values = new ArrayList<>();
                while (result.next()) values.add(read(result));
                return List.copyOf(values);
            }
        } catch (SQLException exception) {
            throw new SqliteStoreException(
                    SqliteStoreFailure.TRANSACTION_FAILED, "Artifact metadata read failed", exception);
        }
    }

    private static Artifact read(ResultSet result) throws SQLException {
        var payload = new ArtifactPayloadRef(
                result.getString("payload_id"),
                result.getString("payload_sha256"),
                result.getLong("payload_byte_count"),
                result.getString("payload_media_type"));
        var provenance = new ArtifactProvenance(
                new ProjectRef(result.getString("project_id")),
                result.getString("workspace_ref"),
                new AgentRunId(result.getString("run_id")),
                new AgentSessionId(result.getString("session_id")),
                result.getString("file_change_set_ref"),
                result.getString("execution_ref"),
                result.getString("source_logical_path"),
                result.getString("source_hash"),
                result.getString("export_policy"),
                new PrincipalRef(result.getString("created_by_id"), result.getString("created_by_type")));
        return new Artifact(
                new ArtifactId(result.getString("artifact_id")),
                new ArtifactVersion(result.getLong("artifact_version")),
                new ArtifactType(result.getString("artifact_type")),
                result.getString("title"),
                payload,
                provenance,
                ArtifactStatus.valueOf(result.getString("status")),
                Instant.ofEpochMilli(result.getLong("created_at_ms")));
    }
}
