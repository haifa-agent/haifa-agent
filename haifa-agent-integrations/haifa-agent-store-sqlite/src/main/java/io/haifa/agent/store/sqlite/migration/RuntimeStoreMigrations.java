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

    private RuntimeStoreMigrations() {}

    public static List<SqliteMigration> all() {
        try {
            return List.of(
                    read(1, "runtime_store", V1_RESOURCE),
                    read(2, "lossless_runtime_fields", V2_RESOURCE),
                    read(3, "policy_approval_security", V3_RESOURCE));
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
