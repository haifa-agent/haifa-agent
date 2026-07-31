package io.haifa.agent.personalassistant.server.configuration.model;

import io.haifa.agent.personalassistant.application.PersonalModelPreference;
import io.haifa.agent.personalassistant.application.PersonalModelPreferenceStore;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

/** Small product-owned SQLite projection sharing the Personal Assistant database file. */
public final class SqlitePersonalModelPreferenceStore implements PersonalModelPreferenceStore {
    private final String jdbcUrl;
    private final String tenantId;
    private final String principalId;

    public SqlitePersonalModelPreferenceStore(Path database, String tenantId, String principalId) {
        jdbcUrl = "jdbc:sqlite:" + database.toAbsolutePath().normalize();
        this.tenantId = tenantId;
        this.principalId = principalId;
        execute(
                """
                CREATE TABLE IF NOT EXISTS personal_model_preference (
                    conversation_id TEXT PRIMARY KEY NOT NULL,
                    tenant_id TEXT NOT NULL,
                    principal_id TEXT NOT NULL,
                    model_id TEXT NOT NULL,
                    revision INTEGER NOT NULL,
                    idempotency_key_digest TEXT,
                    request_digest TEXT,
                    updated_at_ms INTEGER NOT NULL,
                    CHECK (revision >= 0)
                )
                """);
    }

    @Override
    public synchronized PersonalModelPreference create(String conversationId, String modelId, Instant at) {
        Optional<PersonalModelPreference> existing = find(conversationId);
        if (existing.isPresent()) return existing.orElseThrow();
        try (var connection = DriverManager.getConnection(jdbcUrl);
                var statement = connection.prepareStatement(
                        """
                        INSERT INTO personal_model_preference(
                            conversation_id, tenant_id, principal_id, model_id, revision, updated_at_ms
                        ) VALUES (?, ?, ?, ?, 0, ?)
                        """)) {
            statement.setString(1, conversationId);
            statement.setString(2, tenantId);
            statement.setString(3, principalId);
            statement.setString(4, modelId);
            statement.setLong(5, at.toEpochMilli());
            statement.executeUpdate();
            return find(conversationId).orElseThrow();
        } catch (SQLException exception) {
            throw failure(exception);
        }
    }

    @Override
    public synchronized Optional<PersonalModelPreference> find(String conversationId) {
        try (var connection = DriverManager.getConnection(jdbcUrl);
                var statement = connection.prepareStatement(
                        """
                        SELECT * FROM personal_model_preference
                        WHERE conversation_id = ? AND tenant_id = ? AND principal_id = ?
                        """)) {
            statement.setString(1, conversationId);
            statement.setString(2, tenantId);
            statement.setString(3, principalId);
            try (var result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.of(new PersonalModelPreference(
                        conversationId,
                        result.getString("model_id"),
                        result.getLong("revision"),
                        Optional.ofNullable(result.getString("idempotency_key_digest")),
                        Optional.ofNullable(result.getString("request_digest")),
                        Instant.ofEpochMilli(result.getLong("updated_at_ms"))));
            }
        } catch (SQLException exception) {
            throw failure(exception);
        }
    }

    @Override
    public synchronized PersonalModelPreference change(
            String conversationId,
            long expectedRevision,
            String modelId,
            String idempotencyKeyDigest,
            String requestDigest,
            Instant at) {
        PersonalModelPreference current =
                find(conversationId).orElseThrow(() -> new IllegalStateException("MODEL_SELECTION_REQUIRED"));
        if (current.idempotencyKeyDigest().filter(idempotencyKeyDigest::equals).isPresent()) {
            if (current.requestDigest().filter(requestDigest::equals).isEmpty()) {
                throw new IllegalStateException("MODEL_IDEMPOTENCY_CONFLICT");
            }
            return current;
        }
        try (var connection = DriverManager.getConnection(jdbcUrl);
                var statement = connection.prepareStatement(
                        """
                        UPDATE personal_model_preference
                        SET model_id = ?, revision = revision + 1, idempotency_key_digest = ?,
                            request_digest = ?, updated_at_ms = ?
                        WHERE conversation_id = ? AND tenant_id = ? AND principal_id = ? AND revision = ?
                        """)) {
            statement.setString(1, modelId);
            statement.setString(2, idempotencyKeyDigest);
            statement.setString(3, requestDigest);
            statement.setLong(4, at.toEpochMilli());
            statement.setString(5, conversationId);
            statement.setString(6, tenantId);
            statement.setString(7, principalId);
            statement.setLong(8, expectedRevision);
            if (statement.executeUpdate() != 1) throw new IllegalStateException("MODEL_REVISION_STALE");
            return find(conversationId).orElseThrow();
        } catch (SQLException exception) {
            throw failure(exception);
        }
    }

    private void execute(String sql) {
        try (var connection = DriverManager.getConnection(jdbcUrl);
                var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException exception) {
            throw failure(exception);
        }
    }

    private static IllegalStateException failure(SQLException exception) {
        return new IllegalStateException("PERSONAL_MODEL_PREFERENCE_STORE_FAILED", exception);
    }
}
