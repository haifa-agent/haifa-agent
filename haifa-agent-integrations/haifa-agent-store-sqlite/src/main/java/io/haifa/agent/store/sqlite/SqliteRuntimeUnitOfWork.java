package io.haifa.agent.store.sqlite;

import io.haifa.agent.runtime.core.storage.RuntimeUnitOfWork;
import io.haifa.agent.store.sqlite.mybatis.SqliteMyBatisSessionFactory;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.function.Supplier;
import org.apache.ibatis.session.SqlSession;

public final class SqliteRuntimeUnitOfWork implements RuntimeUnitOfWork {
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
            return executeNested(existing, work);
        }
        return executeOutermost(work);
    }

    public <T> T mapper(Class<T> mapperType) {
        return requireContext().session().getMapper(Objects.requireNonNull(mapperType, "mapperType must not be null"));
    }

    public boolean isActive() {
        return active.get() != null;
    }

    Connection currentConnection() {
        return requireContext().connection();
    }

    SqlSession currentSession() {
        return requireContext().session();
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

    private <T> T executeOutermost(Supplier<T> work) {
        try (Connection connection = connections.openConnection();
                SqlSession session = myBatis.openSession(connection)) {
            Context context = new Context(connection, session);
            active.set(context);
            boolean transactionStarted = false;
            try {
                executeControl(connection, "BEGIN IMMEDIATE");
                transactionStarted = true;
                T result = work.get();
                if (context.rollbackOnly) {
                    throw new SqliteStoreException(
                            SqliteStoreFailure.TRANSACTION_FAILED,
                            "SQLite unit of work was marked rollback-only by a nested failure");
                }
                session.flushStatements();
                executeControl(connection, "COMMIT");
                transactionStarted = false;
                return result;
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

    private static void rollback(Connection connection, Throwable original) {
        try {
            executeControl(connection, "ROLLBACK");
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private static final class Context {
        private final Connection connection;
        private final SqlSession session;
        private int depth = 1;
        private boolean rollbackOnly;

        private Context(Connection connection, SqlSession session) {
            this.connection = connection;
            this.session = session;
        }

        private Connection connection() {
            return connection;
        }

        private SqlSession session() {
            return session;
        }
    }
}
