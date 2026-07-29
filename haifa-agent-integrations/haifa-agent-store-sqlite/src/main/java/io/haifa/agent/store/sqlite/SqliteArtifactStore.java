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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Immutable Artifact metadata adapter backed by the shared SQLite unit of work. */
public final class SqliteArtifactStore implements ArtifactStore {
    private static final String SELECT_COLUMNS =
            """
            SELECT a.artifact_id,a.artifact_version,a.artifact_type,a.title,a.status,a.created_at,
                   a.payload_id,a.payload_sha256,a.payload_byte_count,a.payload_media_type,
                   a.project_id,a.workspace_ref,a.run_id,a.session_id,a.file_change_set_ref,a.execution_ref,
                   a.source_logical_path,a.source_hash,a.export_policy,
                   a.created_principal_id,a.created_principal_type,
                   p.sha256,p.byte_count,p.payload,p.reference_count
            FROM artifact_record a LEFT JOIN artifact_payload p ON p.payload_id=a.payload_id
            """;

    private final SqliteRuntimeUnitOfWork unitOfWork;

    public SqliteArtifactStore(SqliteRuntimeUnitOfWork unitOfWork) {
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
    }

    @Override
    public void create(Artifact artifact) {
        Objects.requireNonNull(artifact, "artifact must not be null");
        execute(() -> {
            verifyPayload(artifact.payload());
            String sql =
                    """
                    INSERT INTO artifact_record(
                        artifact_id,artifact_version,artifact_type,title,status,created_at,
                        payload_id,payload_sha256,payload_byte_count,payload_media_type,
                        project_id,workspace_ref,run_id,session_id,file_change_set_ref,execution_ref,
                        source_logical_path,source_hash,export_policy,created_principal_id,created_principal_type)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """;
            try (PreparedStatement statement = unitOfWork.currentConnection().prepareStatement(sql)) {
                int index = 1;
                statement.setString(index++, artifact.id().value());
                statement.setLong(index++, artifact.version().value());
                statement.setString(index++, artifact.type().value());
                statement.setString(index++, artifact.title());
                statement.setString(index++, artifact.status().name());
                statement.setLong(index++, artifact.createdAt().toEpochMilli());
                statement.setString(index++, artifact.payload().payloadId());
                statement.setString(index++, artifact.payload().sha256());
                statement.setLong(index++, artifact.payload().byteCount());
                statement.setString(index++, artifact.payload().mediaType());
                ArtifactProvenance provenance = artifact.provenance();
                statement.setString(index++, provenance.project().projectId());
                statement.setString(index++, provenance.workspaceRef());
                setNullable(
                        statement,
                        index++,
                        provenance.runId() == null ? null : provenance.runId().value());
                setNullable(
                        statement,
                        index++,
                        provenance.sessionId() == null
                                ? null
                                : provenance.sessionId().value());
                setNullable(statement, index++, provenance.fileChangeSetRef());
                setNullable(statement, index++, provenance.executionRef());
                statement.setString(index++, provenance.sourceLogicalPath());
                statement.setString(index++, provenance.sourceHash());
                statement.setString(index++, provenance.exportPolicy());
                statement.setString(index++, provenance.createdBy().principalId());
                statement.setString(index, provenance.createdBy().principalType());
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("ARTIFACT_CREATE_FAILED");
                }
            } catch (SQLException exception) {
                if (exception.getErrorCode() == 19) {
                    throw new IllegalStateException("ARTIFACT_VERSION_ALREADY_EXISTS", exception);
                }
                throw failure(exception);
            }
            return null;
        });
    }

    @Override
    public Optional<Artifact> find(ArtifactId id, ArtifactVersion version) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(version, "version must not be null");
        return execute(() -> {
            String sql = SELECT_COLUMNS + " WHERE a.artifact_id=? AND a.artifact_version=?";
            try (PreparedStatement statement = unitOfWork.currentConnection().prepareStatement(sql)) {
                statement.setString(1, id.value());
                statement.setLong(2, version.value());
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) return Optional.empty();
                    return Optional.of(map(result));
                }
            } catch (SQLException exception) {
                throw failure(exception);
            }
        });
    }

    @Override
    public List<Artifact> findByProject(String projectId) {
        String normalized = requireText(projectId, "projectId");
        return execute(() -> {
            String sql = SELECT_COLUMNS + " WHERE a.project_id=? ORDER BY a.artifact_id ASC,a.artifact_version ASC";
            try (PreparedStatement statement = unitOfWork.currentConnection().prepareStatement(sql)) {
                statement.setString(1, normalized);
                try (ResultSet result = statement.executeQuery()) {
                    List<Artifact> values = new ArrayList<>();
                    while (result.next()) values.add(map(result));
                    return List.copyOf(values);
                }
            } catch (SQLException exception) {
                throw failure(exception);
            }
        });
    }

    private Artifact map(ResultSet result) throws SQLException {
        try {
            ArtifactPayloadRef payload = new ArtifactPayloadRef(
                    result.getString(7), result.getString(8), result.getLong(9), result.getString(10));
            verifyJoinedPayload(
                    payload, result.getString(22), result.getLong(23), result.getBytes(24), result.getInt(25));
            String runId = result.getString(13);
            String sessionId = result.getString(14);
            ArtifactProvenance provenance = new ArtifactProvenance(
                    new ProjectRef(result.getString(11)),
                    result.getString(12),
                    runId == null ? null : new AgentRunId(runId),
                    sessionId == null ? null : new AgentSessionId(sessionId),
                    result.getString(15),
                    result.getString(16),
                    result.getString(17),
                    result.getString(18),
                    result.getString(19),
                    new PrincipalRef(result.getString(20), result.getString(21)));
            return new Artifact(
                    new ArtifactId(result.getString(1)),
                    new ArtifactVersion(result.getLong(2)),
                    new ArtifactType(result.getString(3)),
                    result.getString(4),
                    payload,
                    provenance,
                    ArtifactStatus.valueOf(result.getString(5)),
                    java.time.Instant.ofEpochMilli(result.getLong(6)));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("ARTIFACT_METADATA_CORRUPT", exception);
        }
    }

    private void verifyPayload(ArtifactPayloadRef reference) {
        String sql = "SELECT sha256,byte_count,payload,reference_count FROM artifact_payload WHERE payload_id=?";
        try (PreparedStatement statement = unitOfWork.currentConnection().prepareStatement(sql)) {
            statement.setString(1, reference.payloadId());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalStateException("ARTIFACT_PAYLOAD_MISSING");
                verifyJoinedPayload(
                        reference, result.getString(1), result.getLong(2), result.getBytes(3), result.getInt(4));
            }
        } catch (SQLException exception) {
            throw failure(exception);
        }
    }

    private static void verifyJoinedPayload(
            ArtifactPayloadRef reference, String sha256, long byteCount, byte[] payload, int referenceCount) {
        if (!reference.sha256().equals(sha256)
                || reference.byteCount() != byteCount
                || payload == null
                || payload.length != byteCount
                || referenceCount < 1
                || !hash(payload).equals(sha256)) {
            throw new IllegalStateException("ARTIFACT_PAYLOAD_CORRUPT");
        }
    }

    private <T> T execute(Supplier<T> work) {
        try {
            return unitOfWork.execute(work);
        } catch (SqliteStoreException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) throw runtimeException;
            throw exception;
        }
    }

    private static void setNullable(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) statement.setNull(index, Types.VARCHAR);
        else statement.setString(index, value);
    }

    private static String hash(byte[] bytes) {
        try {
            return "sha256:"
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }

    private static IllegalStateException failure(SQLException exception) {
        return new IllegalStateException("SQLite Artifact metadata operation failed", exception);
    }
}
