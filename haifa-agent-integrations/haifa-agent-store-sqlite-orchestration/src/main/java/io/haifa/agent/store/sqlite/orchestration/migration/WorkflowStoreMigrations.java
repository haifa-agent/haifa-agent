package io.haifa.agent.store.sqlite.orchestration.migration;

import io.haifa.agent.store.sqlite.SqliteStoreException;
import io.haifa.agent.store.sqlite.SqliteStoreFailure;
import io.haifa.agent.store.sqlite.migration.RuntimeStoreMigrations;
import io.haifa.agent.store.sqlite.migration.SqliteMigration;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Authoritative catalog for the explicitly installed SQLite Workflow extension. */
public final class WorkflowStoreMigrations {
    private static final String V12_RESOURCE =
            "/io/haifa/agent/store/sqlite/orchestration/migration/V12__workflow_recovery.sql";
    private static final String V13_RESOURCE =
            "/io/haifa/agent/store/sqlite/orchestration/migration/V13__workflow_subgraph.sql";
    private static final List<SqliteMigration> EXTENSIONS = loadExtensions();
    private static final List<SqliteMigration> COMPLETE = combineWithRuntime();

    private WorkflowStoreMigrations() {}

    public static List<SqliteMigration> extensions() {
        return EXTENSIONS;
    }

    /** Runtime V1-V11 followed by the opt-in Workflow extension from V12. */
    public static List<SqliteMigration> complete() {
        return COMPLETE;
    }

    public static long currentVersion() {
        return COMPLETE.getLast().version();
    }

    private static List<SqliteMigration> loadExtensions() {
        try {
            return List.of(read(12, "workflow_recovery", V12_RESOURCE), read(13, "workflow_subgraph", V13_RESOURCE));
        } catch (IOException exception) {
            throw new SqliteStoreException(
                    SqliteStoreFailure.MIGRATION_FAILED, "Unable to read bundled SQLite Workflow migration", exception);
        }
    }

    private static List<SqliteMigration> combineWithRuntime() {
        if (EXTENSIONS.getFirst().version() != RuntimeStoreMigrations.currentVersion() + 1) {
            throw new SqliteStoreException(
                    SqliteStoreFailure.MIGRATION_FAILED,
                    "SQLite Workflow migrations must immediately follow the Runtime schema");
        }
        List<SqliteMigration> migrations = new ArrayList<>(RuntimeStoreMigrations.all());
        migrations.addAll(EXTENSIONS);
        return List.copyOf(migrations);
    }

    private static SqliteMigration read(long version, String name, String resource) throws IOException {
        try (InputStream input = WorkflowStoreMigrations.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new SqliteStoreException(
                        SqliteStoreFailure.MIGRATION_FAILED,
                        "Bundled SQLite Workflow migration is missing: " + resource);
            }
            return SqliteMigration.fromScript(version, name, new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
