package io.haifa.agent.store.sqlite;

import io.haifa.agent.common.io.SecureFilePermissions;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opens connections to the store database.
 *
 * <p>Physical SQLite connections are reused: {@link #openConnection()} leases one from a small
 * pool and closing the returned connection gives it back. A lease is single-use; once closed it
 * rejects further calls. Every returned connection is restored to the state of a freshly opened
 * one, and a connection that cannot be restored is discarded instead of being reused.
 */
public final class SqliteConnectionFactory implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(SqliteConnectionFactory.class);
    private static final long SLOW_OPERATION_MILLIS = 50;
    /**
     * SQLite admits one writer at a time and units of work are short, so a few connections serve
     * the concurrent readers; each physical connection keeps its own page cache.
     */
    static final int MAXIMUM_POOLED_CONNECTIONS = 8;

    private final SqliteStoreConfiguration configuration;
    private final PermissionStrategyDetector permissionStrategyDetector;
    // Leases never outnumber physical connections, so the pool itself never has to wait for, or
    // reclaim, a connection that is still in use.
    private final Semaphore leases = new Semaphore(MAXIMUM_POOLED_CONNECTIONS, true);
    private final AtomicLong physicalConnectionsOpened = new AtomicLong();
    private volatile PooledDataSource pool;
    private SecureFilePermissions.PermissionStrategy permissionStrategy;
    private volatile boolean initialized;
    private volatile boolean closed;

    public SqliteConnectionFactory(SqliteStoreConfiguration configuration) {
        this(configuration, SecureFilePermissions::strategyForDirectory);
    }

    SqliteConnectionFactory(
            SqliteStoreConfiguration configuration, PermissionStrategyDetector permissionStrategyDetector) {
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        this.permissionStrategyDetector =
                Objects.requireNonNull(permissionStrategyDetector, "permissionStrategyDetector must not be null");
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
            validateConnectionPragmas(connection, false);
            secureDatabaseFiles();
            pool = newPool();
            initialized = true;
        } catch (SQLException exception) {
            throw new SqliteStoreException(
                    SqliteStoreFailure.CONNECTION_FAILED, "Unable to initialize SQLite database", exception);
        }
    }

    public Connection openConnection() {
        long started = System.nanoTime();
        requireOpen();
        if (!initialized) {
            throw new SqliteStoreException(
                    SqliteStoreFailure.CONNECTION_FAILED, "SQLite connection factory is not initialized");
        }
        acquireLease();
        Connection pooled = null;
        try {
            PooledDataSource current = pool;
            if (current == null) {
                throw new SqliteStoreException(
                        SqliteStoreFailure.CONNECTION_FAILED, "SQLite connection factory is closed");
            }
            pooled = current.getConnection();
            long leaseMillis = elapsedMillis(started);
            long phaseStarted = System.nanoTime();
            secureDatabaseFiles();
            long secureMillis = elapsedMillis(phaseStarted);
            requireOpen();
            Connection lease = lease(pooled);
            logConnectionOpen(leaseMillis, secureMillis, elapsedMillis(started));
            return lease;
        } catch (RuntimeException exception) {
            discard(pooled, exception);
            leases.release();
            throw exception;
        } catch (SQLException exception) {
            var failure = new SqliteStoreException(
                    SqliteStoreFailure.CONNECTION_FAILED, "Unable to open SQLite database", exception);
            discard(pooled, failure);
            leases.release();
            throw failure;
        }
    }

    /** Number of physical connections opened so far; a reused connection is not counted again. */
    long physicalConnectionsOpened() {
        return physicalConnectionsOpened.get();
    }

    public SqliteStoreConfiguration configuration() {
        return configuration;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        PooledDataSource current = pool;
        pool = null;
        permissionStrategy = null;
        if (current != null) {
            current.forceCloseAll();
        }
    }

    private PooledDataSource newPool() {
        PooledDataSource created = new PooledDataSource(new PhysicalConnections());
        created.setPoolMaximumActiveConnections(MAXIMUM_POOLED_CONNECTIONS);
        // Keeping every returned connection idle is the point: closing one costs far more than
        // the transaction it served.
        created.setPoolMaximumIdleConnections(MAXIMUM_POOLED_CONNECTIONS);
        // The pool would otherwise take a long-running unit of work's connection away from it.
        created.setPoolMaximumCheckoutTime(Integer.MAX_VALUE);
        created.setPoolTimeToWait(configuration.busyTimeoutMillis());
        return created;
    }

    private void acquireLease() {
        try {
            if (leases.tryAcquire(configuration.busyTimeoutMillis(), TimeUnit.MILLISECONDS)) {
                return;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SqliteStoreException(
                    SqliteStoreFailure.CONNECTION_FAILED,
                    "Interrupted while waiting for a SQLite connection",
                    exception);
        }
        throw new SqliteStoreException(
                SqliteStoreFailure.DATABASE_BUSY,
                "Timed out waiting for one of the " + MAXIMUM_POOLED_CONNECTIONS + " pooled SQLite connections");
    }

    private Connection lease(Connection pooled) {
        return (Connection) Proxy.newProxyInstance(
                SqliteConnectionFactory.class.getClassLoader(), new Class<?>[] {Connection.class}, new Lease(pooled));
    }

    private void giveBack(Connection pooled) {
        try {
            boolean reusable = !closed && restoreFreshState(pooled);
            if (!reusable) {
                closePhysical(pooled);
            }
            // Hands a healthy connection back to the pool; a closed one is dropped by it.
            pooled.close();
        } catch (SQLException exception) {
            LOGGER.debug("event=sqlite.connection.discard reason=return-failed", exception);
        } finally {
            leases.release();
        }
    }

    private static void discard(Connection pooled, RuntimeException original) {
        if (pooled == null) return;
        try {
            closePhysical(pooled);
            pooled.close();
        } catch (SQLException closeFailure) {
            original.addSuppressed(closeFailure);
        }
    }

    private static void closePhysical(Connection pooled) throws SQLException {
        PooledDataSource.unwrapConnection(pooled).close();
    }

    /**
     * Leaves the connection as a newly opened one would be: not inside a transaction and writable.
     * The units of work drive transactions with BEGIN/COMMIT statements, which JDBC's auto-commit
     * flag does not reflect, so an open transaction is ended explicitly here.
     */
    private static boolean restoreFreshState(Connection connection) {
        try {
            if (!connection.getAutoCommit()) {
                connection.rollback();
                connection.setAutoCommit(true);
            }
            try (Statement statement = connection.createStatement()) {
                try {
                    statement.execute("ROLLBACK");
                } catch (SQLException exception) {
                    if (!isNoActiveTransaction(exception)) {
                        throw exception;
                    }
                }
                statement.execute("PRAGMA query_only=OFF");
            }
            return true;
        } catch (SQLException exception) {
            LOGGER.debug("event=sqlite.connection.discard reason=restore-failed", exception);
            return false;
        }
    }

    private static boolean isNoActiveTransaction(SQLException exception) {
        String message = exception.getMessage();
        return message != null && message.contains("no transaction is active");
    }

    private Connection openRawConnection() {
        long started = System.nanoTime();
        requireOpen();
        Connection connection = null;
        try {
            long phaseStarted = System.nanoTime();
            connection = DriverManager.getConnection("jdbc:sqlite:" + configuration.databasePath());
            long driverMillis = elapsedMillis(phaseStarted);
            phaseStarted = System.nanoTime();
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys=ON");
                statement.execute("PRAGMA busy_timeout=" + configuration.busyTimeoutMillis());
            }
            long pragmaMillis = elapsedMillis(phaseStarted);
            logRawConnection(driverMillis, pragmaMillis, elapsedMillis(started));
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
            Path directory = configuration.databasePath().getParent();
            permissionStrategy = permissionStrategyDetector.detect(directory);
            permissionStrategy.secureDirectory(directory);
        } catch (IOException exception) {
            throw new SqliteStoreException(
                    SqliteStoreFailure.FILE_PERMISSION_FAILED,
                    "Unable to apply secure SQLite directory permissions",
                    exception);
        }
    }

    private void secureDatabaseFiles() {
        SecureFilePermissions.PermissionStrategy strategy =
                Objects.requireNonNull(permissionStrategy, "SQLite permission strategy must be initialized");
        Path database = configuration.databasePath();
        try {
            strategy.secureExistingFiles(List.of(
                    database,
                    database.resolveSibling(database.getFileName() + "-wal"),
                    database.resolveSibling(database.getFileName() + "-shm"),
                    database.resolveSibling(database.getFileName() + "-journal")));
        } catch (IOException exception) {
            throw new SqliteStoreException(
                    SqliteStoreFailure.FILE_PERMISSION_FAILED,
                    "Unable to validate and secure SQLite database files",
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

    private static long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static void logRawConnection(long driverMillis, long pragmaMillis, long totalMillis) {
        if (totalMillis >= SLOW_OPERATION_MILLIS) {
            LOGGER.info(
                    "event=sqlite.connection.raw driverMs={} pragmaMs={} totalMs={}",
                    driverMillis,
                    pragmaMillis,
                    totalMillis);
        } else {
            LOGGER.debug(
                    "event=sqlite.connection.raw driverMs={} pragmaMs={} totalMs={}",
                    driverMillis,
                    pragmaMillis,
                    totalMillis);
        }
    }

    private static void logConnectionOpen(long leaseMillis, long secureMillis, long totalMillis) {
        if (totalMillis >= SLOW_OPERATION_MILLIS) {
            LOGGER.info(
                    "event=sqlite.connection.open leaseMs={} secureFilesMs={} totalMs={}",
                    leaseMillis,
                    secureMillis,
                    totalMillis);
        } else {
            LOGGER.debug(
                    "event=sqlite.connection.open leaseMs={} secureFilesMs={} totalMs={}",
                    leaseMillis,
                    secureMillis,
                    totalMillis);
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new SqliteStoreException(SqliteStoreFailure.CONNECTION_FAILED, "SQLite connection factory is closed");
        }
    }

    /** Supplies the pool with physical connections that carry the store's connection settings. */
    private final class PhysicalConnections extends UnpooledDataSource {
        @Override
        public Connection getConnection() {
            Connection connection = openRawConnection();
            try {
                validateConnectionPragmas(connection, false);
            } catch (RuntimeException exception) {
                closeFailedConnection(connection, exception);
                throw exception;
            }
            physicalConnectionsOpened.incrementAndGet();
            return connection;
        }

        @Override
        public Connection getConnection(String username, String password) {
            return getConnection();
        }
    }

    /** A single-use view of a pooled connection; closing it returns the connection to the pool. */
    private final class Lease implements InvocationHandler {
        private final Connection pooled;
        private final AtomicBoolean returned = new AtomicBoolean();

        private Lease(Connection pooled) {
            this.pooled = pooled;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            switch (method.getName()) {
                case "close" -> {
                    if (returned.compareAndSet(false, true)) {
                        giveBack(pooled);
                    }
                    return null;
                }
                case "isClosed" -> {
                    return returned.get() || pooled.isClosed();
                }
                case "equals" -> {
                    return proxy == arguments[0];
                }
                case "hashCode" -> {
                    return System.identityHashCode(proxy);
                }
                case "toString" -> {
                    return "SqliteConnectionLease[returned=" + returned.get() + "]";
                }
                default -> {
                    if (returned.get()) {
                        throw new SQLException("SQLite connection was already returned to the store");
                    }
                    try {
                        return method.invoke(pooled, arguments);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                }
            }
        }
    }

    @FunctionalInterface
    interface PermissionStrategyDetector {
        SecureFilePermissions.PermissionStrategy detect(Path directory) throws IOException;
    }
}
