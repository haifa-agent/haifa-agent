package io.haifa.agent.store.sqlite;

import io.haifa.agent.common.io.SecureFilePermissions;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class SqliteConnectionFactory implements AutoCloseable {
    private final SqliteStoreConfiguration configuration;
    private final Set<WeakReference<Connection>> openedConnections = ConcurrentHashMap.newKeySet();
    private volatile boolean initialized;
    private volatile boolean closed;

    public SqliteConnectionFactory(SqliteStoreConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
    }

    public synchronized void initialize() {
        requireOpen();
        if (initialized) {
            return;
        }
        secureDatabaseDirectory();
        try (Connection connection = openRawConnection();
                Statement statement = connection.createStatement()) {
            String journalMode;
            try (ResultSet result = statement.executeQuery("PRAGMA journal_mode=WAL")) {
                if (!result.next()) {
                    throw pragmaFailure("SQLite did not return a journal mode");
                }
                journalMode = result.getString(1);
            }
            if (!"wal".equals(journalMode.toLowerCase(Locale.ROOT))) {
                throw pragmaFailure("SQLite WAL mode is unavailable");
            }
            validateConnectionPragmas(connection, true);
            secureDatabaseFiles();
            initialized = true;
        } catch (SQLException exception) {
            throw new SqliteStoreException(
                    SqliteStoreFailure.CONNECTION_FAILED, "Unable to initialize SQLite database", exception);
        }
    }

    public synchronized Connection openConnection() {
        requireOpen();
        if (!initialized) {
            throw new SqliteStoreException(
                    SqliteStoreFailure.CONNECTION_FAILED, "SQLite connection factory is not initialized");
        }
        Connection connection = openRawConnection();
        try {
            validateConnectionPragmas(connection, true);
            secureDatabaseFiles();
            pruneClosedConnections();
            openedConnections.add(new WeakReference<>(connection));
            return connection;
        } catch (RuntimeException exception) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw exception;
        }
    }

    public SqliteStoreConfiguration configuration() {
        return configuration;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        for (WeakReference<Connection> reference : openedConnections) {
            Connection connection = reference.get();
            if (connection == null) continue;
            try {
                connection.close();
            } catch (SQLException exception) {
                if (failure == null) {
                    failure = new SqliteStoreException(
                            SqliteStoreFailure.CONNECTION_FAILED,
                            "Unable to close SQLite store connections",
                            exception);
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        openedConnections.clear();
        if (failure != null) throw failure;
    }

    private void pruneClosedConnections() {
        openedConnections.removeIf(reference -> {
            Connection connection = reference.get();
            if (connection == null) return true;
            try {
                return connection.isClosed();
            } catch (SQLException ignored) {
                return false;
            }
        });
    }

    private Connection openRawConnection() {
        requireOpen();
        Connection connection = null;
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + configuration.databasePath());
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys=ON");
                statement.execute("PRAGMA busy_timeout=" + configuration.busyTimeoutMillis());
            }
            secureDatabaseFiles();
            return connection;
        } catch (RuntimeException exception) {
            closeFailedConnection(connection, exception);
            throw exception;
        } catch (SQLException exception) {
            var failure = new SqliteStoreException(
                    SqliteStoreFailure.CONNECTION_FAILED, "Unable to open SQLite database", exception);
            closeFailedConnection(connection, failure);
            throw failure;
        }
    }

    private static void closeFailedConnection(Connection connection, RuntimeException original) {
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException closeFailure) {
            original.addSuppressed(closeFailure);
        }
    }

    private void secureDatabaseDirectory() {
        try {
            SecureFilePermissions.secureDirectory(configuration.databasePath().getParent());
        } catch (IOException exception) {
            throw new SqliteStoreException(
                    SqliteStoreFailure.FILE_PERMISSION_FAILED,
                    "Unable to apply secure SQLite directory permissions",
                    exception);
        }
    }

    private void secureDatabaseFiles() {
        Path database = configuration.databasePath();
        secureFileIfPresent(database);
        secureFileIfPresent(database.resolveSibling(database.getFileName() + "-wal"));
        secureFileIfPresent(database.resolveSibling(database.getFileName() + "-shm"));
        secureFileIfPresent(database.resolveSibling(database.getFileName() + "-journal"));
    }

    private static void secureFileIfPresent(Path path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            SecureFilePermissions.secureFile(path);
        } catch (NoSuchFileException ignored) {
            // WAL/SHM files may disappear between the existence check and ACL update when another
            // connection checkpoints. The secured parent directory governs any replacement file.
        } catch (IOException exception) {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
            throw new SqliteStoreException(
                    SqliteStoreFailure.FILE_PERMISSION_FAILED,
                    "Unable to apply secure SQLite file permissions",
                    exception);
        }
    }

    private void validateConnectionPragmas(Connection connection, boolean requireWal) {
        try {
            if (queryLong(connection, "PRAGMA foreign_keys") != 1L) {
                throw pragmaFailure("SQLite foreign key enforcement is disabled");
            }
            if (queryLong(connection, "PRAGMA busy_timeout") != configuration.busyTimeoutMillis()) {
                throw pragmaFailure("SQLite busy timeout does not match the configured value");
            }
            if (requireWal) {
                String mode = queryString(connection, "PRAGMA journal_mode");
                if (!"wal".equals(mode.toLowerCase(Locale.ROOT))) {
                    throw pragmaFailure("SQLite connection is not using WAL");
                }
            }
        } catch (SQLException exception) {
            throw new SqliteStoreException(
                    SqliteStoreFailure.PRAGMA_VALIDATION_FAILED,
                    "Unable to validate SQLite connection settings",
                    exception);
        }
    }

    private static long queryLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) {
                throw new SQLException("PRAGMA returned no row");
            }
            return result.getLong(1);
        }
    }

    private static String queryString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) {
                throw new SQLException("PRAGMA returned no row");
            }
            return Objects.requireNonNull(result.getString(1), "PRAGMA value must not be null");
        }
    }

    private static SqliteStoreException pragmaFailure(String message) {
        return new SqliteStoreException(SqliteStoreFailure.PRAGMA_VALIDATION_FAILED, message);
    }

    private void requireOpen() {
        if (closed) {
            throw new SqliteStoreException(SqliteStoreFailure.CONNECTION_FAILED, "SQLite connection factory is closed");
        }
    }
}
