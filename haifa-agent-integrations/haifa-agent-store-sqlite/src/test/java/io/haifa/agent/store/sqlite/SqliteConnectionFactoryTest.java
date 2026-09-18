package io.haifa.agent.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.common.io.SecureFilePermissions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteConnectionFactoryTest {
    @TempDir
    Path directory;

    @Test
    void requiresControlledAbsoluteDatabasePathAndUsableParent() throws Exception {
        assertThatThrownBy(() -> SqliteStoreConfiguration.defaults(Path.of("relative.db")))
                .isInstanceOf(SqliteStoreException.class)
                .extracting(exception -> ((SqliteStoreException) exception).failure())
                .isEqualTo(SqliteStoreFailure.INVALID_CONFIGURATION);

        assertThatThrownBy(() -> SqliteStoreConfiguration.defaults(
                        directory.resolve("missing").resolve("runtime.db").toAbsolutePath()))
                .isInstanceOf(SqliteStoreException.class)
                .hasMessageContaining("existing directory");

        Path nonDirectoryParent = Files.writeString(directory.resolve("not-a-directory"), "blocked");
        assertThatThrownBy(() -> SqliteStoreConfiguration.defaults(
                        nonDirectoryParent.resolve("runtime.db").toAbsolutePath()))
                .isInstanceOf(SqliteStoreException.class)
                .hasMessageContaining("existing directory");

        assertThatThrownBy(() -> SqliteStoreConfiguration.defaults(directory.toAbsolutePath()))
                .isInstanceOf(SqliteStoreException.class)
                .hasMessageContaining("regular file");
    }

    @Test
    void initializesWalAndConfiguresEveryConnection() throws Exception {
        SqliteStoreConfiguration configuration = SqliteTestSupport.configuration(directory);
        SqliteConnectionFactory factory = new SqliteConnectionFactory(configuration);
        factory.initialize();
        factory.initialize();

        try (Connection first = factory.openConnection();
                Connection second = factory.openConnection()) {
            assertThat(query(first, "PRAGMA journal_mode")).isEqualToIgnoringCase("wal");
            assertThat(query(first, "PRAGMA foreign_keys")).isEqualTo("1");
            assertThat(query(first, "PRAGMA busy_timeout"))
                    .isEqualTo(Integer.toString(configuration.busyTimeoutMillis()));
            assertThat(query(second, "PRAGMA foreign_keys")).isEqualTo("1");
            assertThat(query(second, "PRAGMA busy_timeout"))
                    .isEqualTo(Integer.toString(configuration.busyTimeoutMillis()));
        }
    }

    @Test
    void detectsThePermissionStrategyOnceAndRevalidatesExistingFilesOnEveryOpen() throws Exception {
        SqliteStoreConfiguration configuration = SqliteTestSupport.configuration(directory);
        AtomicInteger detections = new AtomicInteger();
        AtomicInteger standaloneRootValidations = new AtomicInteger();
        AtomicInteger securedBatches = new AtomicInteger();
        Map<Path, AtomicInteger> securedFiles = new HashMap<>();
        SqliteConnectionFactory.PermissionStrategyDetector detector = root -> {
            detections.incrementAndGet();
            var delegate = SecureFilePermissions.strategyForDirectory(root);
            return new SecureFilePermissions.PermissionStrategy() {
                @Override
                public void validateRoot() throws java.io.IOException {
                    standaloneRootValidations.incrementAndGet();
                    delegate.validateRoot();
                }

                @Override
                public void secureDirectory(Path value) throws java.io.IOException {
                    delegate.secureDirectory(value);
                }

                @Override
                public void secureFile(Path value) throws java.io.IOException {
                    securedFiles
                            .computeIfAbsent(value, ignored -> new AtomicInteger())
                            .incrementAndGet();
                    delegate.secureFile(value);
                }

                @Override
                public void secureExistingFiles(java.util.List<Path> values) throws java.io.IOException {
                    securedBatches.incrementAndGet();
                    values.stream().filter(Files::exists).forEach(value -> securedFiles
                            .computeIfAbsent(value, ignored -> new AtomicInteger())
                            .incrementAndGet());
                    delegate.secureExistingFiles(values);
                }
            };
        };
        SqliteConnectionFactory factory = new SqliteConnectionFactory(configuration, detector);
        factory.initialize();

        try (Connection first = factory.openConnection()) {
            int afterFirst = total(securedFiles);
            try (Connection second = factory.openConnection()) {
                assertThat(total(securedFiles)).isGreaterThan(afterFirst);
            }
        }

        Path database = configuration.databasePath().toAbsolutePath().normalize();
        int securedBeforeReplacement = securedFiles.get(database).get();
        Path replacement = directory.resolve("replacement.db");
        try (Connection ignored = java.sql.DriverManager.getConnection("jdbc:sqlite:" + replacement)) {
            // Create a valid database whose storage identity differs from the active database.
        }
        Files.move(replacement, database, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try (Connection ignored = factory.openConnection()) {
            assertThat(securedFiles.get(database).get()).isEqualTo(securedBeforeReplacement + 1);
        }

        assertThat(detections).hasValue(1);
        assertThat(standaloneRootValidations).hasValue(0);
        assertThat(securedBatches).hasValueGreaterThanOrEqualTo(4);
        factory.close();
    }

    @Test
    void rejectsAnExistingSymbolicDatabaseBeforeJdbcCanFollowIt() throws Exception {
        Path target = Files.writeString(directory.resolve("target.db"), "not a database");
        Path link = directory.resolve("linked.db");
        try {
            Files.createSymbolicLink(link, target.getFileName());
        } catch (java.io.IOException | UnsupportedOperationException | SecurityException exception) {
            org.junit.jupiter.api.Assumptions.abort("symbolic links are unavailable on this host");
        }

        assertThatThrownBy(() -> SqliteStoreConfiguration.defaults(link.toAbsolutePath()))
                .isInstanceOf(SqliteStoreException.class)
                .hasMessageContaining("regular file");
    }

    private static int total(Map<Path, AtomicInteger> values) {
        return values.values().stream().mapToInt(AtomicInteger::get).sum();
    }

    private static String query(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }
}
