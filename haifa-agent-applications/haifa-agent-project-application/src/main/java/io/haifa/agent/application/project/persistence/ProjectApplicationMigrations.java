package io.haifa.agent.application.project.persistence;

import io.haifa.agent.store.sqlite.SqliteStoreException;
import io.haifa.agent.store.sqlite.SqliteStoreFailure;
import io.haifa.agent.store.sqlite.migration.RuntimeStoreMigrations;
import io.haifa.agent.store.sqlite.migration.SqliteMigration;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class ProjectApplicationMigrations {
    private static final String RESOURCE =
            "/io/haifa/agent/application/project/persistence/V1000__project_product_session.sql";

    private ProjectApplicationMigrations() {}

    static List<SqliteMigration> all() {
        var migrations = new ArrayList<>(RuntimeStoreMigrations.all());
        migrations.add(read());
        return List.copyOf(migrations);
    }

    private static SqliteMigration read() {
        try (InputStream input = ProjectApplicationMigrations.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new SqliteStoreException(
                        SqliteStoreFailure.MIGRATION_FAILED, "Bundled Project Application migration is missing");
            }
            return SqliteMigration.fromScript(
                    1_000, "project_product_session", new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new SqliteStoreException(
                    SqliteStoreFailure.MIGRATION_FAILED,
                    "Unable to read bundled Project Application migration",
                    exception);
        }
    }
}
