package io.haifa.agent.personalassistant.server.configuration.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.personalassistant.application.PersonalModelPreference;
import io.haifa.agent.personalassistant.application.PersonalModelPreferenceDraft;
import io.haifa.agent.personalassistant.application.PersonalModelPreferenceStore;
import io.haifa.agent.personalassistant.application.PersonalModelPreferences;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** Small product-owned SQLite projection sharing the Personal Assistant database file. */
public final class SqlitePersonalModelPreferenceStore implements PersonalModelPreferenceStore {
    private static final String CREATE_TABLE_SQL =
            """
            CREATE TABLE IF NOT EXISTS personal_model_preference (
                conversation_id TEXT PRIMARY KEY NOT NULL,
                tenant_id TEXT NOT NULL,
                principal_id TEXT NOT NULL,
                model_binding_id TEXT NOT NULL,
                preference_schema_version TEXT NOT NULL,
                user_preferences_json TEXT NOT NULL,
                preference_digest TEXT NOT NULL,
                revision INTEGER NOT NULL,
                idempotency_key_digest TEXT,
                request_digest TEXT,
                updated_at_ms INTEGER NOT NULL,
                CHECK (revision >= 0)
            )
            """;

    private final String jdbcUrl;
    private final String tenantId;
    private final String principalId;
    private final ObjectMapper mapper;

    public SqlitePersonalModelPreferenceStore(Path database, String tenantId, String principalId) {
        this(database, tenantId, principalId, new ObjectMapper().findAndRegisterModules());
    }

    public SqlitePersonalModelPreferenceStore(Path database, String tenantId, String principalId, ObjectMapper mapper) {
        jdbcUrl = "jdbc:sqlite:" + database.toAbsolutePath().normalize();
        this.tenantId = tenantId;
        this.principalId = principalId;
        this.mapper = mapper;
        execute(CREATE_TABLE_SQL);
        if (!hasExpectedSchema()) rebuildPreferenceTableOnly();
    }

    @Override
    public synchronized PersonalModelPreference create(
            String conversationId, PersonalModelPreferenceDraft preference, Instant at) {
        Optional<PersonalModelPreference> existing = find(conversationId);
        if (existing.isPresent()) return existing.orElseThrow();
        try (var connection = DriverManager.getConnection(jdbcUrl);
                var statement = connection.prepareStatement(
                        """
                        INSERT INTO personal_model_preference(
                            conversation_id, tenant_id, principal_id, model_binding_id,
                            preference_schema_version, user_preferences_json, preference_digest,
                            revision, updated_at_ms
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?)
                        """)) {
            statement.setString(1, conversationId);
            statement.setString(2, tenantId);
            statement.setString(3, principalId);
            statement.setString(4, preference.modelBindingId());
            statement.setString(5, preference.preferenceSchemaVersion());
            statement.setString(6, json(preference.userPreferences()));
            statement.setString(7, preference.preferenceDigest());
            statement.setLong(8, at.toEpochMilli());
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
                        result.getString("model_binding_id"),
                        result.getString("preference_schema_version"),
                        preferences(result.getString("user_preferences_json")),
                        result.getString("preference_digest"),
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
            PersonalModelPreferenceDraft preference,
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
                        SET model_binding_id = ?, preference_schema_version = ?, user_preferences_json = ?,
                            preference_digest = ?, revision = revision + 1, idempotency_key_digest = ?,
                            request_digest = ?, updated_at_ms = ?
                        WHERE conversation_id = ? AND tenant_id = ? AND principal_id = ? AND revision = ?
                        """)) {
            statement.setString(1, preference.modelBindingId());
            statement.setString(2, preference.preferenceSchemaVersion());
            statement.setString(3, json(preference.userPreferences()));
            statement.setString(4, preference.preferenceDigest());
            statement.setString(5, idempotencyKeyDigest);
            statement.setString(6, requestDigest);
            statement.setLong(7, at.toEpochMilli());
            statement.setString(8, conversationId);
            statement.setString(9, tenantId);
            statement.setString(10, principalId);
            statement.setLong(11, expectedRevision);
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

    private boolean hasExpectedSchema() {
        Set<String> expected = Set.of(
                "conversation_id",
                "tenant_id",
                "principal_id",
                "model_binding_id",
                "preference_schema_version",
                "user_preferences_json",
                "preference_digest",
                "revision",
                "idempotency_key_digest",
                "request_digest",
                "updated_at_ms");
        Set<String> actual = new HashSet<>();
        try (var connection = DriverManager.getConnection(jdbcUrl);
                var statement = connection.createStatement();
                var result = statement.executeQuery("PRAGMA table_info(personal_model_preference)")) {
            while (result.next()) actual.add(result.getString("name"));
        } catch (SQLException exception) {
            throw failure(exception);
        }
        return actual.equals(expected);
    }

    private void rebuildPreferenceTableOnly() {
        try (var connection = DriverManager.getConnection(jdbcUrl);
                var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.execute("DROP TABLE personal_model_preference");
                statement.execute(CREATE_TABLE_SQL);
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw failure(exception);
        }
    }

    private static IllegalStateException failure(SQLException exception) {
        return new IllegalStateException("PERSONAL_MODEL_PREFERENCE_STORE_FAILED", exception);
    }

    private String json(PersonalModelPreferences preferences) {
        try {
            return mapper.writeValueAsString(preferences);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("PERSONAL_MODEL_PREFERENCE_SERIALIZATION_FAILED", exception);
        }
    }

    private PersonalModelPreferences preferences(String json) {
        try {
            return mapper.readValue(json, PersonalModelPreferences.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("PERSONAL_MODEL_PREFERENCE_SERIALIZATION_FAILED", exception);
        }
    }
}
