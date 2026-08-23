package io.haifa.agent.store.sqlite.migration;

import io.haifa.agent.store.sqlite.SqliteStoreException;
import io.haifa.agent.store.sqlite.SqliteStoreFailure;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class RuntimeStoreMigrations {
    private static final String V1_RESOURCE = "/io/haifa/agent/store/sqlite/migration/V1__runtime_store.sql";
    private static final String V2_RESOURCE = "/io/haifa/agent/store/sqlite/migration/V2__lossless_runtime_fields.sql";
    private static final String V3_RESOURCE = "/io/haifa/agent/store/sqlite/migration/V3__policy_approval_security.sql";
    private static final String V4_RESOURCE =
            "/io/haifa/agent/store/sqlite/migration/V4__interaction_event_journal.sql";
    private static final String V5_RESOURCE = "/io/haifa/agent/store/sqlite/migration/V5__sdk_conversation.sql";
    private static final String V6_RESOURCE = "/io/haifa/agent/store/sqlite/migration/V6__memory_foundation.sql";
    private static final String V7_RESOURCE = "/io/haifa/agent/store/sqlite/migration/V7__artifact_foundation.sql";
    private static final String V8_RESOURCE =
            "/io/haifa/agent/store/sqlite/migration/V8__tool_reconciliation_evidence.sql";
    private static final List<SqliteMigration> MIGRATIONS = load();

    private RuntimeStoreMigrations() {}

    public static List<SqliteMigration> all() {
        return MIGRATIONS;
    }

    /** Returns the last bundled Runtime migration version from the authoritative catalog. */
    public static long currentVersion() {
        return MIGRATIONS.getLast().version();
    }

    private static List<SqliteMigration> load() {
        try {
            return List.of(
                    read(1, "runtime_store", V1_RESOURCE),
                    read(2, "lossless_runtime_fields", V2_RESOURCE),
                    read(3, "policy_approval_security", V3_RESOURCE),
                    read(4, "interaction_event_journal", V4_RESOURCE),
                    read(5, "sdk_conversation", V5_RESOURCE),
                    read(6, "memory_foundation", V6_RESOURCE),
                    read(7, "artifact_foundation", V7_RESOURCE),
                    read(8, "tool_reconciliation_evidence", V8_RESOURCE));
        } catch (IOException exception) {
            throw new SqliteStoreException(
                    SqliteStoreFailure.MIGRATION_FAILED, "Unable to read bundled SQLite migration", exception);
        }
    }

    private static SqliteMigration read(long version, String name, String resource) throws IOException {
        try (InputStream input = RuntimeStoreMigrations.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new SqliteStoreException(
                        SqliteStoreFailure.MIGRATION_FAILED, "Bundled SQLite migration is missing: " + resource);
            }
            return SqliteMigration.fromScript(version, name, new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
