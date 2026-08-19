package io.haifa.agent.store.sqlite;

import io.haifa.agent.orchestration.core.spi.WorkflowUnitOfWork;
import io.haifa.agent.runtime.core.storage.RuntimeUnitOfWork;
import io.haifa.agent.store.sqlite.mybatis.SqliteMyBatisSessionFactory;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.apache.ibatis.session.SqlSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SqliteRuntimeUnitOfWork implements RuntimeUnitOfWork, WorkflowUnitOfWork {
    private static final Logger LOGGER = LoggerFactory.getLogger(SqliteRuntimeUnitOfWork.class);
    private static final AtomicLong UNIT_OF_WORK_IDS = new AtomicLong();
    private static final long SLOW_OPERATION_MILLIS = 50;
    private static final int MAX_BEGIN_ATTEMPTS = 2;

    private final SqliteConnectionFactory connections;
    private final SqliteMyBatisSessionFactory myBatis;
    private final ThreadLocal<Context> active = new ThreadLocal<>();

    public SqliteRuntimeUnitOfWork(SqliteConnectionFactory connections, SqliteMyBatisSessionFactory myBatis) {
        this.connections = Objects.requireNonNull(connections, "connections must not be null");
        this.myBatis = Objects.requireNonNull(myBatis, "myBatis must not be null");
    }

    @Override
    public <T> T execute(Supplier<T> work) {
        Objects.requireNonNull(work, "work must not be null");
        Context existing = active.get();
        if (existing != null) {
            if (existing.readOnly()) {
                throw new SqliteStoreException(
                        SqliteStoreFailure.TRANSACTION_FAILED,
                        "A write unit of work cannot start inside a read-only SQLite unit of work");
            }
            return executeNested(existing, work);
        }
        return executeOutermost(work, TransactionMode.WRITE);
    }

    /**
     * Executes an adapter-internal read without reserving SQLite's single writer slot.
     *
     * <p>A read nested inside an existing write joins that transaction so it can observe the
     * caller's uncommitted state. Outermost reads use a deferred, query-only transaction.
     */
    <T> T executeReadOnly(Supplier<T> work) {
        Objects.requireNonNull(work, "work must not be null");
        Context existing = active.get();
        if (existing != null) {
            return executeNested(existing, work);
        }
        return executeOutermost(work, TransactionMode.READ_ONLY);
    }

    public <T> T mapper(Class<T> mapperType) {
        return requireContext().session().getMapper(Objects.requireNonNull(mapperType, "mapperType must not be null"));
    }

    public boolean isActive() {
        return active.get() != null;
    }

    long currentId() {
        return requireContext().id();
    }

    Connection currentConnection() {
        return requireContext().connection();
    }

    SqlSession currentSession() {
        return requireContext().session();
    }

    @Override
    public void afterCommit(Runnable listener) {
        requireContext().afterCommit.add(Objects.requireNonNull(listener, "listener must not be null"));
    }

    private <T> T executeNested(Context context, Supplier<T> work) {
        context.depth++;
        try {
            return work.get();
        } catch (RuntimeException | Error exception) {
            context.rollbackOnly = true;
            throw exception;
        } finally {
            context.depth--;
        }
    }

    private <T> T executeOutermost(Supplier<T> work, TransactionMode mode) {
        long started = System.nanoTime();
        long unitOfWorkId = UNIT_OF_WORK_IDS.incrementAndGet();
        T result;
        java.util.List<Runnable> committedListeners;
        try (Connection connection = connections.openConnection();
                SqlSession session = myBatis.openSession(connection)) {
            long setupMillis = elapsedMillis(started);
            Context context = new Context(unitOfWorkId, connection, session, mode == TransactionMode.READ_ONLY);
            active.set(context);
            boolean transactionStarted = false;
            try {
                long phaseStarted = System.nanoTime();
                begin(connection, mode);
                long beginMillis = elapsedMillis(phaseStarted);
                transactionStarted = true;
                phaseStarted = System.nanoTime();
                result = work.get();
                long workMillis = elapsedMillis(phaseStarted);
                if (context.rollbackOnly) {
                    throw new SqliteStoreException(
                            SqliteStoreFailure.TRANSACTION_FAILED,
                            "SQLite unit of work was marked rollback-only by a nested failure");
                }
                phaseStarted = System.nanoTime();
                session.flushStatements();
                long flushMillis = elapsedMillis(phaseStarted);
                phaseStarted = System.nanoTime();
                executeControl(connection, "COMMIT");
                long commitMillis = elapsedMillis(phaseStarted);
                transactionStarted = false;
                committedListeners = java.util.List.copyOf(context.afterCommit);
                logUnitOfWork(
                        unitOfWorkId,
                        mode,
                        setupMillis,
                        beginMillis,
                        workMillis,
                        flushMillis,
                        commitMillis,
                        committedListeners.size(),
                        elapsedMillis(started));
            } catch (RuntimeException | Error | SQLException exception) {
                if (transactionStarted) {
                    rollback(connection, exception);
                }
                if (exception instanceof SqliteStoreException storeException) {
                    throw storeException;
                }
                throw new SqliteStoreException(
                        SqliteStoreFailure.TRANSACTION_FAILED, "SQLite unit of work failed", exception);
            } finally {
                active.remove();
            }
        } catch (SQLException exception) {
            throw new SqliteStoreException(
                    SqliteStoreFailure.TRANSACTION_FAILED, "Unable to close SQLite unit of work", exception);
        }
        committedListeners.forEach(Runnable::run);
        return result;
    }

    private static long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static void logUnitOfWork(
            long unitOfWorkId,
            TransactionMode mode,
            long setupMillis,
            long beginMillis,
            long workMillis,
            long flushMillis,
            long commitMillis,
            int listenerCount,
            long totalMillis) {
        if (totalMillis >= SLOW_OPERATION_MILLIS) {
            LOGGER.info(
                    "event=sqlite.uow uowId={} transactionMode={} setupMs={} beginMs={} workMs={} flushMs={} commitMs={} afterCommitListeners={} totalMs={}",
                    unitOfWorkId,
                    mode,
                    setupMillis,
                    beginMillis,
                    workMillis,
                    flushMillis,
                    commitMillis,
                    listenerCount,
                    totalMillis);
        } else {
            LOGGER.debug(
                    "event=sqlite.uow uowId={} transactionMode={} setupMs={} beginMs={} workMs={} flushMs={} commitMs={} afterCommitListeners={} totalMs={}",
                    unitOfWorkId,
                    mode,
                    setupMillis,
                    beginMillis,
                    workMillis,
                    flushMillis,
                    commitMillis,
                    listenerCount,
                    totalMillis);
        }
    }

    private Context requireContext() {
        Context context = active.get();
        if (context == null) {
            throw new SqliteStoreException(
                    SqliteStoreFailure.NO_ACTIVE_UNIT_OF_WORK, "No SQLite unit of work is active on this thread");
        }
        return context;
    }

    private static void executeControl(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void beginImmediate(Connection connection) throws SQLException {
        SQLException busyFailure = null;
        for (int attempt = 1; attempt <= MAX_BEGIN_ATTEMPTS; attempt++) {
            try {
                executeControl(connection, "BEGIN IMMEDIATE");
                return;
            } catch (SQLException exception) {
                if (!isBusy(exception)) throw exception;
                busyFailure = exception;
                if (attempt < MAX_BEGIN_ATTEMPTS) {
                    LOGGER.debug("event=sqlite.uow.begin-retry attempt={} maxAttempts={}", attempt, MAX_BEGIN_ATTEMPTS);
                }
            }
        }
        throw new SqliteStoreException(
                SqliteStoreFailure.DATABASE_BUSY, "SQLite writer is busy before the unit of work began", busyFailure);
    }

    private static void begin(Connection connection, TransactionMode mode) throws SQLException {
        if (mode == TransactionMode.WRITE) {
            beginImmediate(connection);
            return;
        }
        executeControl(connection, "PRAGMA query_only=ON");
        executeControl(connection, "BEGIN");
    }

    private static boolean isBusy(SQLException exception) {
        return exception.getErrorCode() == 5 || exception.getErrorCode() == 6;
    }

    private static void rollback(Connection connection, Throwable original) {
        try {
            executeControl(connection, "ROLLBACK");
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private static final class Context {
        private final long id;
        private final Connection connection;
        private final SqlSession session;
        private final boolean readOnly;
        private int depth = 1;
        private boolean rollbackOnly;
        private final java.util.List<Runnable> afterCommit = new java.util.ArrayList<>();

        private Context(long id, Connection connection, SqlSession session, boolean readOnly) {
            this.id = id;
            this.connection = connection;
            this.session = session;
            this.readOnly = readOnly;
        }

        private long id() {
            return id;
        }

        private Connection connection() {
            return connection;
        }

        private SqlSession session() {
            return session;
        }

        private boolean readOnly() {
            return readOnly;
        }
    }

    private enum TransactionMode {
        READ_ONLY,
        WRITE
    }
}
