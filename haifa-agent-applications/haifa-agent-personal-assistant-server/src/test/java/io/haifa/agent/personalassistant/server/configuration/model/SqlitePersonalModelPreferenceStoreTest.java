package io.haifa.agent.personalassistant.server.configuration.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.personalassistant.application.PersonalModelPreferenceDraft;
import io.haifa.agent.personalassistant.application.PersonalModelPreferences;
import io.haifa.agent.personalassistant.application.PersonalResponseLength;
import io.haifa.agent.personalassistant.application.PersonalResponseMode;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlitePersonalModelPreferenceStoreTest {
    @TempDir
    Path directory;

    @Test
    void restoresSelectionAndProtectsRevisionAndIdempotency() {
        Path database = directory.resolve("personal.sqlite");
        var first = new SqlitePersonalModelPreferenceStore(database, "tenant", "principal");
        first.create("conversation-1", draft("deepseek", PersonalResponseLength.RECOMMENDED), Instant.EPOCH);
        var changed = first.change(
                "conversation-1",
                0,
                draft("bailian", PersonalResponseLength.LONG),
                "key-a",
                "request-a",
                Instant.ofEpochSecond(1));

        var reopened = new SqlitePersonalModelPreferenceStore(database, "tenant", "principal");
        assertThat(reopened.find("conversation-1")).contains(changed);
        assertThat(reopened.change(
                        "conversation-1",
                        0,
                        draft("bailian", PersonalResponseLength.LONG),
                        "key-a",
                        "request-a",
                        Instant.ofEpochSecond(2)))
                .isEqualTo(changed);
        assertThatThrownBy(() -> reopened.change(
                        "conversation-1",
                        1,
                        draft("deepseek", PersonalResponseLength.SHORT),
                        "key-a",
                        "request-b",
                        Instant.ofEpochSecond(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MODEL_IDEMPOTENCY_CONFLICT");
        assertThatThrownBy(() -> reopened.change(
                        "conversation-1",
                        0,
                        draft("deepseek", PersonalResponseLength.SHORT),
                        "key-b",
                        "request-c",
                        Instant.ofEpochSecond(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MODEL_REVISION_STALE");
        assertThat(changed.modelBindingId()).isEqualTo("bailian");
        assertThat(changed.userPreferences().responseLength()).isEqualTo(PersonalResponseLength.LONG);
        assertThat(changed.preferenceDigest())
                .isEqualTo(changed.userPreferences().digest());
        assertThat(columns(database))
                .containsExactlyInAnyOrder(
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
    }

    @Test
    void rebuildsOnlyThePreReleasePreferenceTableWhenItsSchemaIsIncompatible() throws Exception {
        Path database = directory.resolve("legacy.sqlite");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var statement = connection.createStatement()) {
            statement.execute(
                    """
                    CREATE TABLE personal_model_preference (
                        conversation_id TEXT PRIMARY KEY NOT NULL,
                        model_id TEXT NOT NULL,
                        revision INTEGER NOT NULL
                    )
                    """);
            statement.execute("INSERT INTO personal_model_preference VALUES ('old-conversation', 'old-model', 0)");
            statement.execute("CREATE TABLE unrelated_product_data (id TEXT PRIMARY KEY, value TEXT NOT NULL)");
            statement.execute("INSERT INTO unrelated_product_data VALUES ('keep-me', 'preserved')");
        }

        var rebuilt = new SqlitePersonalModelPreferenceStore(database, "tenant", "principal");

        assertThat(rebuilt.find("old-conversation")).isEmpty();
        assertThat(columns(database))
                .containsExactlyInAnyOrder(
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
        assertThat(unrelatedValue(database)).isEqualTo("preserved");
    }

    private static Set<String> columns(Path database) {
        Set<String> columns = new HashSet<>();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var statement = connection.createStatement();
                var result = statement.executeQuery("PRAGMA table_info(personal_model_preference)")) {
            while (result.next()) columns.add(result.getString("name"));
            return columns;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String unrelatedValue(Path database) {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT value FROM unrelated_product_data WHERE id='keep-me'")) {
            return result.next() ? result.getString(1) : null;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static PersonalModelPreferenceDraft draft(String bindingId, PersonalResponseLength length) {
        var preferences =
                new PersonalModelPreferences(PersonalResponseMode.RECOMMENDED, java.util.Optional.empty(), length);
        return new PersonalModelPreferenceDraft(bindingId, "1.0", preferences, preferences.digest());
    }
}
