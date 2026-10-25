package io.haifa.agent.store.sqlite;

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

public final class SqliteRuntimeUnitOfWork implements RuntimeUnitOfWork {
    private static final Logger LOGGER = LoggerFactory.getLogger(SqliteRuntimeUnitOfWork.class);
    private static final AtomicLong UNIT_OF_WORK_IDS = new AtomicLong();
    private static final long SLOW_OPERATION_MILLIS = 50;
    private static final String COMMITTED = "committed";
    private static final String FAILED = "failed";
    /** Attempts to begin a write transaction while another process holds the database write lock. */
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
        boolean writerHeld = false;
        long writerWaitMillis = 0;
        try {
            if (mode == TransactionMode.WRITE) {
                // Queue for the write slot before leasing a connection: a queued writer must not occupy one of the
                // pooled connections that the current holder, or a nested caller, still needs.
                writerWaitMillis = connections.acquireWriter();
                writerHeld = true;
            }
            try (Connection connection = connections.openConnection();
                    SqlSession session = myBatis.openSession(connection)) {
                long setupMillis = elapsedMillis(started);
                Context context = new Context(unitOfWorkId, connection, session, mode == TransactionMode.READ_ONLY);
                active.set(context);
                boolean transactionStarted = false;
                long beginMillis = 0;
                long workMillis = 0;
                long flushMillis = 0;
                long commitMillis = 0;
                try {
                    long phaseStarted = System.nanoTime();
                    begin(connection, mode);
                    beginMillis = elapsedMillis(phaseStarted);
                    transactionStarted = true;
                    phaseStarted = System.nanoTime();
                    result = work.get();
                    workMillis = elapsedMillis(phaseStarted);
                    if (context.rollbackOnly) {
                        throw new SqliteStoreException(
                                SqliteStoreFailure.TRANSACTION_FAILED,
                                "SQLite unit of work was marked rollback-only by a nested failure");
                    }
                    phaseStarted = System.nanoTime();
                    session.flushStatements();
                    flushMillis = elapsedMillis(phaseStarted);
                    phaseStarted = System.nanoTime();
                    executeControl(connection, "COMMIT");
                    commitMillis = elapsedMillis(phaseStarted);
                    transactionStarted = false;
                    committedListeners = java.util.List.copyOf(context.afterCommit);
                    logUnitOfWork(
                            unitOfWorkId,
                            new UnitOfWorkTiming(
                                    mode,
                                    writerWaitMillis,
                                    setupMillis,
                                    beginMillis,
                                    workMillis,
                                    flushMillis,
                                    commitMillis,
                                    elapsedMillis(started)),
                            COMMITTED,
                            "",
                            committedListeners.size());
                } catch (RuntimeException | Error | SQLException exception) {
                    if (transactionStarted) {
                        rollback(connection, exception);
                    }
                    RuntimeException failure = exception instanceof SqliteStoreException storeException
                            ? storeException
                            : new SqliteStoreException(
                                    SqliteStoreFailure.TRANSACTION_FAILED, "SQLite unit of work failed", exception);
                    // The failure path is logged too: a unit of work that never committed used to leave no trace,
                    // which made a slow or failed holder of the write slot impossible to identify.
                    logUnitOfWork(
                            unitOfWorkId,
                            new UnitOfWorkTiming(
                                    mode,
                                    writerWaitMillis,
                                    setupMillis,
                                    beginMillis,
                                    workMillis,
                                    flushMillis,
                                    commitMillis,
                                    elapsedMillis(started)),
                            FAILED,
                            failureCode(failure),
                            context.afterCommit.size());
                    throw failure;
                } finally {
                    active.remove();
                }
            } catch (SQLException exception) {
                throw new SqliteStoreException(
                        SqliteStoreFailure.TRANSACTION_FAILED, "Unable to close SQLite unit of work", exception);
            }
        } finally {
            // Released after the connection is closed, so the next writer never shares this unit of work's lease.
            if (writerHeld) {
                connections.releaseWriter();
            }
        }
        committedListeners.forEach(Runnable::run);
        return result;
    }

    private static long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static String failureCode(RuntimeException failure) {
        return failure instanceof SqliteStoreException storeException
                ? storeException.failure().name()
                : SqliteStoreFailure.TRANSACTION_FAILED.name();
    }

    private static void logUnitOfWork(
            long unitOfWorkId, UnitOfWorkTiming timing, String outcome, String failure, int listenerCount) {
        boolean worthReporting = FAILED.equals(outcome) || timing.totalMillis() >= SLOW_OPERATION_MILLIS;
        if (worthReporting) {
            LOGGER.info(
                    "event=sqlite.uow uowId={} transactionMode={} outcome={} waitMs={} setupMs={} beginMs={} workMs={} flushMs={} commitMs={} afterCommitListeners={} totalMs={} failure={}",
                    unitOfWorkId,
                    timing.mode(),
                    outcome,
                    timing.writerWaitMillis(),
                    timing.setupMillis(),
                    timing.beginMillis(),
                    timing.workMillis(),
                    timing.flushMillis(),
                    timing.commitMillis(),
                    listenerCount,
                    timing.totalMillis(),
                    failure);
        } else {
            LOGGER.debug(
                    "event=sqlite.uow uowId={} transactionMode={} outcome={} waitMs={} setupMs={} beginMs={} workMs={} flushMs={} commitMs={} afterCommitListeners={} totalMs={} failure={}",
                    unitOfWorkId,
                    timing.mode(),
                    outcome,
                    timing.writerWaitMillis(),
                    timing.setupMillis(),
                    timing.beginMillis(),
                    timing.workMillis(),
                    timing.flushMillis(),
                    timing.commitMillis(),
                    listenerCount,
                    timing.totalMillis(),
                    failure);
        }
    }

    /**
     * Phase breakdown of one unit of work. {@code waitMillis} is the time spent queued for the run's write slot,
     * which no other phase covers: it happens before the transaction begins and after the connection is leased.
     */
    private record UnitOfWorkTiming(
            TransactionMode mode,
            long writerWaitMillis,
            long setupMillis,
            long beginMillis,
            long workMillis,
            long flushMillis,
            long commitMillis,
            long totalMillis) {}

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
