package io.haifa.agent.store.sqlite.migration;

import io.haifa.agent.store.sqlite.SqliteStoreException;
import io.haifa.agent.store.sqlite.SqliteStoreFailure;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** The single physical SQLite schema history shared by every SQLite-backed product. */
public final class HaifaAgentStoreMigrations {
    public static final int CURRENT_SCHEMA_VERSION = 1_007;
    private static final String RESOURCE_ROOT = "/io/haifa/agent/store/sqlite/migration/";
    private static final List<RegisteredMigration> MIGRATIONS = List.of(
            new RegisteredMigration(1, "runtime_store", "V1__runtime_store.sql"),
            new RegisteredMigration(2, "lossless_runtime_fields", "V2__lossless_runtime_fields.sql"),
            new RegisteredMigration(4, "interaction_event_journal", "V4__interaction_event_journal.sql"),
            new RegisteredMigration(5, "sdk_conversation", "V5__sdk_conversation.sql"),
            new RegisteredMigration(6, "memory_foundation", "V6__memory_foundation.sql"),
            new RegisteredMigration(7, "artifact_foundation", "V7__artifact_foundation.sql"),
            new RegisteredMigration(8, "tool_reconciliation_evidence", "V8__tool_reconciliation_evidence.sql"),
            new RegisteredMigration(9, "optional_interaction_expiry", "V9__optional_interaction_expiry.sql"),
            new RegisteredMigration(10, "human_wait_timing", "V10__human_wait_timing.sql"),
            new RegisteredMigration(11, "separate_run_limits", "V11__separate_run_limits.sql"),
            new RegisteredMigration(1_000, "project_product_session", "V1000__project_product_session.sql"),
            new RegisteredMigration(1_001, "coding_session_product_loop", "V1001__coding_session_product_loop.sql"),
            new RegisteredMigration(1_002, "coding_session_event_cursor", "V1002__coding_session_event_cursor.sql"),
            new RegisteredMigration(1_003, "coding_session_management", "V1003__coding_session_management.sql"),
            new RegisteredMigration(
                    1_004, "coding_session_model_preference", "V1004__coding_session_model_preference.sql"),
            new RegisteredMigration(1_005, "coding_delivery_intent", "V1005__coding_delivery_intent.sql"),
            new RegisteredMigration(
                    1_006, "coding_follow_up_dispatched_run_index", "V1006__coding_follow_up_dispatched_run_index.sql"),
            new RegisteredMigration(1_007, "coding_workspace_registry", "V1007__coding_workspace_registry.sql"));

    private HaifaAgentStoreMigrations() {}

    public static List<SqliteMigration> all() {
        return MIGRATIONS.stream().map(HaifaAgentStoreMigrations::read).toList();
    }

    private static SqliteMigration read(RegisteredMigration migration) {
        String resource = RESOURCE_ROOT + migration.resource();
        try (InputStream input = HaifaAgentStoreMigrations.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new SqliteStoreException(
                        SqliteStoreFailure.MIGRATION_FAILED, "Bundled SQLite migration is missing: " + resource);
            }
            return SqliteMigration.fromScript(
                    migration.version(), migration.name(), new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new SqliteStoreException(
                    SqliteStoreFailure.MIGRATION_FAILED, "Unable to read bundled SQLite migration", exception);
        }
    }

    private record RegisteredMigration(long version, String name, String resource) {}
}
