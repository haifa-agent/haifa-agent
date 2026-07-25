package io.haifa.agent.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.store.sqlite.mybatis.MigrationMetadataMapper;
import io.haifa.agent.store.sqlite.mybatis.SqliteMyBatisSessionFactory;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteRuntimeUnitOfWorkTest {
    @TempDir
    Path directory;

    @Test
    void commitsAndRollsBackOnTheSameConnection() throws Exception {
        SqliteStoreFoundation foundation = SqliteTestSupport.foundation(directory);
        SqliteRuntimeUnitOfWork unitOfWork = foundation.unitOfWork();

        unitOfWork.execute(() -> {
            execute(unitOfWork.currentConnection(), "CREATE TABLE uow_values(value TEXT NOT NULL)");
            execute(unitOfWork.currentConnection(), "INSERT INTO uow_values(value) VALUES ('committed')");
            return null;
        });

        assertThatThrownBy(() -> unitOfWork.execute(() -> {
                    execute(unitOfWork.currentConnection(), "INSERT INTO uow_values(value) VALUES ('rolled-back')");
                    throw new IllegalStateException("fail");
                }))
                .isInstanceOf(SqliteStoreException.class)
                .hasCauseInstanceOf(IllegalStateException.class);

        try (Connection connection = foundation.connections().openConnection()) {
            assertThat(queryLong(connection, "SELECT COUNT(*) FROM uow_values")).isEqualTo(1);
        }
    }

    @Test
    void nestedCallsReuseOneConnectionAndSessionAndNestedFailureMarksRollbackOnly() throws Exception {
        SqliteStoreFoundation foundation = SqliteTestSupport.foundation(directory);
        SqliteRuntimeUnitOfWork unitOfWork = foundation.unitOfWork();
        AtomicReference<Connection> outerConnection = new AtomicReference<>();
        AtomicReference<SqlSession> outerSession = new AtomicReference<>();

        unitOfWork.execute(() -> {
            outerConnection.set(unitOfWork.currentConnection());
            outerSession.set(unitOfWork.currentSession());
            assertThat(autoCommit(unitOfWork.currentConnection())).isTrue();
            unitOfWork.execute(() -> {
                assertThat(unitOfWork.currentConnection()).isSameAs(outerConnection.get());
                assertThat(unitOfWork.currentSession()).isSameAs(outerSession.get());
                assertThat(unitOfWork.mapper(MigrationMetadataMapper.class))
                        .isNotNull()
                        .isNotSameAs(unitOfWork.mapper(MigrationMetadataMapper.class));
                return null;
            });
            assertThat(unitOfWork.mapper(MigrationMetadataMapper.class).findByVersion(1))
                    .hasValueSatisfying(row -> assertThat(row.appliedAt()).isEqualTo(SqliteTestSupport.NOW));
            return null;
        });

        assertThatThrownBy(() -> unitOfWork.execute(() -> {
                    execute(unitOfWork.currentConnection(), "CREATE TABLE rollback_only(value TEXT)");
                    try {
                        unitOfWork.execute(() -> {
                            throw new IllegalArgumentException("nested");
                        });
                    } catch (SqliteStoreException | IllegalArgumentException ignored) {
                        // Outer code cannot accidentally commit after a nested failure.
                    }
                    return null;
                }))
                .isInstanceOf(SqliteStoreException.class)
                .hasMessageContaining("rollback-only");

        try (Connection connection = foundation.connections().openConnection()) {
            assertThat(queryLong(
                            connection,
                            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='rollback_only'"))
                    .isZero();
        }
    }

    @Test
    void beginImmediateReservesTheWriterOnTheBoundConnection() {
        SqliteStoreFoundation foundation = SqliteTestSupport.foundation(directory);

        foundation.unitOfWork().execute(() -> {
            try (Connection competitor = foundation.connections().openConnection();
                    Statement statement = competitor.createStatement()) {
                assertThatThrownBy(() -> statement.execute("BEGIN IMMEDIATE"))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("locked");
            } catch (SQLException exception) {
                throw new IllegalStateException(exception);
            }
            return null;
        });
    }

    @Test
    void closingManagedMyBatisSessionDoesNotCloseCommitOrRollbackUowConnection() throws Exception {
        SqliteStoreFoundation foundation = SqliteTestSupport.foundation(directory);
        try (Connection connection = foundation.connections().openConnection()) {
            execute(connection, "BEGIN IMMEDIATE");
            execute(connection, "CREATE TABLE managed_close(value TEXT)");
            SqliteMyBatisSessionFactory myBatis = foundation.myBatis();
            SqlSession session = myBatis.openSession(connection);
            session.close();

            assertThat(connection.isClosed()).isFalse();
            execute(connection, "ROLLBACK");
            assertThat(queryLong(
                            connection,
                            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='managed_close'"))
                    .isZero();
        }
    }

    @Test
    void refusesMapperAccessOutsideUnitOfWork() {
        SqliteRuntimeUnitOfWork unitOfWork =
                SqliteTestSupport.foundation(directory).unitOfWork();

        assertThatThrownBy(() -> unitOfWork.mapper(MigrationMetadataMapper.class))
                .isInstanceOf(SqliteStoreException.class)
                .extracting(exception -> ((SqliteStoreException) exception).failure())
                .isEqualTo(SqliteStoreFailure.NO_ACTIVE_UNIT_OF_WORK);
    }

    private static void execute(Connection connection, String sql) {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception exception) {
            throw new IllegalStateException("test SQL failed", exception);
        }
    }

    private static long queryLong(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private static boolean autoCommit(Connection connection) {
        try {
            return connection.getAutoCommit();
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
