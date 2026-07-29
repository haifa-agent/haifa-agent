package io.haifa.agent.store.sqlite;

import io.haifa.agent.artifact.ArtifactPayloadRef;
import io.haifa.agent.artifact.ArtifactPayloadStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Single-node, bounded SQLite BLOB implementation of the Artifact payload port. */
public final class SqliteArtifactPayloadStore implements ArtifactPayloadStore {
    private final SqliteRuntimeUnitOfWork unitOfWork;
    private final int maximumPayloadBytes;
    private final java.time.Clock clock;

    public SqliteArtifactPayloadStore(
            SqliteRuntimeUnitOfWork unitOfWork, int maximumPayloadBytes, java.time.Clock clock) {
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        if (maximumPayloadBytes < 1) throw new IllegalArgumentException("maximumPayloadBytes must be positive");
        this.maximumPayloadBytes = maximumPayloadBytes;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public ArtifactPayloadRef put(byte[] payload, String mediaType) {
        byte[] immutable = Arrays.copyOf(Objects.requireNonNull(payload, "payload must not be null"), payload.length);
        String normalizedMediaType = requireText(mediaType, "mediaType");
        if (immutable.length > maximumPayloadBytes) {
            throw new IllegalArgumentException("ARTIFACT_PAYLOAD_TOO_LARGE");
        }
        String sha256 = hash(immutable);
        String payloadId = "artifact-payload-" + sha256.substring("sha256:".length());
        return execute(() -> {
            PayloadRow existing = findRow(payloadId);
            if (existing == null) {
                insert(payloadId, sha256, immutable);
            } else {
                verify(existing, payloadId, sha256, immutable.length, immutable);
                increment(payloadId, existing.referenceCount());
            }
            return new ArtifactPayloadRef(payloadId, sha256, immutable.length, normalizedMediaType);
        });
    }

    @Override
    public Optional<byte[]> load(ArtifactPayloadRef reference) {
        Objects.requireNonNull(reference, "reference must not be null");
        return execute(() -> {
            PayloadRow row = findRow(reference.payloadId());
            if (row == null) return Optional.empty();
            verify(row, reference.payloadId(), reference.sha256(), reference.byteCount(), row.payload());
            return Optional.of(Arrays.copyOf(row.payload(), row.payload().length));
        });
    }

    @Override
    public void delete(ArtifactPayloadRef reference) {
        Objects.requireNonNull(reference, "reference must not be null");
        execute(() -> {
            PayloadRow row = findRow(reference.payloadId());
            if (row == null) throw new IllegalStateException("ARTIFACT_PAYLOAD_MISSING");
            verify(row, reference.payloadId(), reference.sha256(), reference.byteCount(), row.payload());
            if (row.referenceCount() == 1) {
                update("DELETE FROM artifact_payload WHERE payload_id=? AND reference_count=1", reference.payloadId());
            } else {
                try (PreparedStatement statement = unitOfWork
                        .currentConnection()
                        .prepareStatement("UPDATE artifact_payload SET reference_count=reference_count-1 "
                                + "WHERE payload_id=? AND reference_count=?")) {
                    statement.setString(1, reference.payloadId());
                    statement.setInt(2, row.referenceCount());
                    if (statement.executeUpdate() != 1) {
                        throw new IllegalStateException("ARTIFACT_PAYLOAD_REFERENCE_CONFLICT");
                    }
                } catch (SQLException exception) {
                    throw failure(exception);
                }
            }
            return null;
        });
    }

    private void insert(String payloadId, String sha256, byte[] payload) {
        String sql = "INSERT INTO artifact_payload(payload_id,sha256,byte_count,payload,reference_count,created_at) "
                + "VALUES(?,?,?,?,1,?)";
        try (PreparedStatement statement = unitOfWork.currentConnection().prepareStatement(sql)) {
            statement.setString(1, payloadId);
            statement.setString(2, sha256);
            statement.setLong(3, payload.length);
            statement.setBytes(4, payload);
            statement.setLong(5, clock.millis());
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("ARTIFACT_PAYLOAD_CREATE_FAILED");
            }
        } catch (SQLException exception) {
            throw failure(exception);
        }
    }

    private void increment(String payloadId, int expectedReferences) {
        try (PreparedStatement statement = unitOfWork
                .currentConnection()
                .prepareStatement("UPDATE artifact_payload SET reference_count=reference_count+1 "
                        + "WHERE payload_id=? AND reference_count=?")) {
            statement.setString(1, payloadId);
            statement.setInt(2, expectedReferences);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("ARTIFACT_PAYLOAD_REFERENCE_CONFLICT");
            }
        } catch (SQLException exception) {
            throw failure(exception);
        }
    }

    private void update(String sql, String payloadId) {
        try (PreparedStatement statement = unitOfWork.currentConnection().prepareStatement(sql)) {
            statement.setString(1, payloadId);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("ARTIFACT_PAYLOAD_REFERENCE_CONFLICT");
            }
        } catch (SQLException exception) {
            throw failure(exception);
        }
    }

    private PayloadRow findRow(String payloadId) {
        String sql =
                "SELECT payload_id,sha256,byte_count,payload,reference_count FROM artifact_payload WHERE payload_id=?";
        try (PreparedStatement statement = unitOfWork.currentConnection().prepareStatement(sql)) {
            statement.setString(1, payloadId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                return new PayloadRow(
                        result.getString(1),
                        result.getString(2),
                        result.getLong(3),
                        result.getBytes(4),
                        result.getInt(5));
            }
        } catch (SQLException exception) {
            throw failure(exception);
        }
    }

    private static void verify(
            PayloadRow row, String payloadId, String sha256, long byteCount, byte[] expectedPayload) {
        byte[] stored = row.payload();
        if (!row.payloadId().equals(payloadId)
                || !row.sha256().equals(sha256)
                || row.byteCount() != byteCount
                || stored == null
                || stored.length != byteCount
                || row.referenceCount() < 1
                || !hash(stored).equals(sha256)
                || !Arrays.equals(stored, expectedPayload)) {
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
        return new IllegalStateException("SQLite Artifact payload operation failed", exception);
    }

    private record PayloadRow(String payloadId, String sha256, long byteCount, byte[] payload, int referenceCount) {}
}
