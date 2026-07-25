package io.haifa.agent.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
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

    private static String query(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }
}
