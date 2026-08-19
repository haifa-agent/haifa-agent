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
    private static final List<ApplicationMigration> APPLICATION_MIGRATIONS = List.of(
            new ApplicationMigration(
                    1_000,
                    "project_product_session",
                    "/io/haifa/agent/application/project/persistence/V1000__project_product_session.sql"),
            new ApplicationMigration(
                    1_001,
                    "coding_session_product_loop",
                    "/io/haifa/agent/application/project/persistence/V1001__coding_session_product_loop.sql"),
            new ApplicationMigration(
                    1_002,
                    "coding_session_event_cursor",
                    "/io/haifa/agent/application/project/persistence/V1002__coding_session_event_cursor.sql"),
            new ApplicationMigration(
                    1_003,
                    "coding_session_management",
                    "/io/haifa/agent/application/project/persistence/V1003__coding_session_management.sql"),
            new ApplicationMigration(
                    1_004,
                    "coding_session_model_preference",
                    "/io/haifa/agent/application/project/persistence/V1004__coding_session_model_preference.sql"),
            new ApplicationMigration(
                    1_005,
                    "coding_delivery_intent",
                    "/io/haifa/agent/application/project/persistence/V1005__coding_delivery_intent.sql"));

    private ProjectApplicationMigrations() {}

    static List<SqliteMigration> all() {
        var migrations = new ArrayList<>(RuntimeStoreMigrations.all());
        APPLICATION_MIGRATIONS.stream().map(ProjectApplicationMigrations::read).forEach(migrations::add);
        return List.copyOf(migrations);
    }

    private static SqliteMigration read(ApplicationMigration migration) {
        try (InputStream input = ProjectApplicationMigrations.class.getResourceAsStream(migration.resource())) {
            if (input == null) {
                throw new SqliteStoreException(
                        SqliteStoreFailure.MIGRATION_FAILED, "Bundled Project Application migration is missing");
            }
            return SqliteMigration.fromScript(
                    migration.version(),
                    migration.description(),
                    new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new SqliteStoreException(
                    SqliteStoreFailure.MIGRATION_FAILED,
                    "Unable to read bundled Project Application migration",
                    exception);
        }
    }

    private record ApplicationMigration(int version, String description, String resource) {}
}
