package io.haifa.agent.store.sqlite.migration;

import io.haifa.agent.store.sqlite.SqliteStoreException;
import io.haifa.agent.store.sqlite.SqliteStoreFailure;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class RuntimeStoreMigrations {
    private static final String V1_RESOURCE = "/io/haifa/agent/store/sqlite/migration/V1__runtime_store.sql";

    private RuntimeStoreMigrations() {}

    public static List<SqliteMigration> all() {
        try (InputStream input = RuntimeStoreMigrations.class.getResourceAsStream(V1_RESOURCE)) {
            if (input == null) {
                throw new SqliteStoreException(
                        SqliteStoreFailure.MIGRATION_FAILED, "Bundled SQLite V1 migration is missing");
            }
            String script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            return List.of(SqliteMigration.fromScript(1, "runtime_store", script));
        } catch (IOException exception) {
            throw new SqliteStoreException(
                    SqliteStoreFailure.MIGRATION_FAILED, "Unable to read bundled SQLite migration", exception);
        }
    }
}
