package io.haifa.agent.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteConnectionReuseTest {
    @TempDir
    Path directory;

    @Test
    void sequentialUnitsOfWorkShareOnePhysicalConnection() throws Exception {
        try (SqliteStoreFoundation foundation =
                SqliteStoreFoundation.initialize(SqliteTestSupport.configuration(directory), SqliteTestSupport.CLOCK)) {
            SqliteRuntimeUnitOfWork unitOfWork = foundation.unitOfWork();
            long openedByMigration = foundation.connections().physicalConnectionsOpened();
            createProbeTable(foundation.connections());

            for (int round = 0; round < 50; round++) {
                unitOfWork.execute(
                        () -> execute(unitOfWork.currentConnection(), "INSERT INTO probe(note) VALUES ('w')"));
                assertThat(unitOfWork.executeReadOnly(() -> count(unitOfWork.currentConnection())))
                        .isEqualTo(round + 1L);
            }

            assertThat(openedByMigration).isEqualTo(1);
            assertThat(foundation.connections().physicalConnectionsOpened()).isEqualTo(1);
        }
    }

    @Test
    void readOnlyAndWriteUnitsOfWorkAlternateOnTheSameConnection() throws Exception {
        try (SqliteStoreFoundation foundation =
                SqliteStoreFoundation.initialize(SqliteTestSupport.configuration(directory), SqliteTestSupport.CLOCK)) {
            SqliteRuntimeUnitOfWork unitOfWork = foundation.unitOfWork();
            createProbeTable(foundation.connections());

            assertThat(unitOfWork.executeReadOnly(() -> count(unitOfWork.currentConnection())))
                    .isZero();
            unitOfWork.execute(() -> execute(unitOfWork.currentConnection(), "INSERT INTO probe(note) VALUES ('w')"));
            assertThat(unitOfWork.executeReadOnly(() -> count(unitOfWork.currentConnection())))
                    .isEqualTo(1);
            unitOfWork.execute(() -> execute(unitOfWork.currentConnection(), "INSERT INTO probe(note) VALUES ('w')"));

            try (Connection connection = foundation.connections().openConnection()) {
                assertThat(queryLong(connection, "PRAGMA query_only")).isZero();
                assertThat(count(connection)).isEqualTo(2);
            }
            assertThat(foundation.connections().physicalConnectionsOpened()).isEqualTo(1);
        }
    }

    @Test
    void failedUnitOfWorkLeavesTheReusedConnectionOutsideAnyTransaction() throws Exception {
        try (SqliteStoreFoundation foundation =
                SqliteStoreFoundation.initialize(SqliteTestSupport.configuration(directory), SqliteTestSupport.CLOCK)) {
            SqliteRuntimeUnitOfWork unitOfWork = foundation.unitOfWork();
            createProbeTable(foundation.connections());

            assertThatThrownBy(() -> unitOfWork.execute(() -> {
                        execute(unitOfWork.currentConnection(), "INSERT INTO probe(note) VALUES ('lost')");
                        throw new IllegalStateException("boom");
                    }))
                    .isInstanceOf(SqliteStoreException.class);

            unitOfWork.execute(
                    () -> execute(unitOfWork.currentConnection(), "INSERT INTO probe(note) VALUES ('kept')"));
            assertThat(unitOfWork.executeReadOnly(() -> count(unitOfWork.currentConnection())))
                    .isEqualTo(1);
            assertThat(foundation.connections().physicalConnectionsOpened()).isEqualTo(1);
        }
    }

    @Test
    void connectionReturnedInsideATransactionIsRolledBackBeforeReuse() throws Exception {
        try (SqliteConnectionFactory factory = initializedFactory()) {
            createProbeTable(factory);

            try (Connection abandoned = factory.openConnection()) {
                execute(abandoned, "BEGIN IMMEDIATE");
                execute(abandoned, "INSERT INTO probe(note) VALUES ('uncommitted')");
            }
            try (Connection jdbcManaged = factory.openConnection()) {
                jdbcManaged.setAutoCommit(false);
                execute(jdbcManaged, "INSERT INTO probe(note) VALUES ('uncommitted')");
            }

            try (Connection reused = factory.openConnection()) {
                assertThat(reused.getAutoCommit()).isTrue();
                assertThat(count(reused)).isZero();
                execute(reused, "BEGIN IMMEDIATE");
                execute(reused, "INSERT INTO probe(note) VALUES ('committed')");
                execute(reused, "COMMIT");
                assertThat(count(reused)).isEqualTo(1);
            }
            assertThat(factory.physicalConnectionsOpened()).isEqualTo(1);
        }
    }

    @Test
    void returnedConnectionRejectsFurtherUse() throws Exception {
        try (SqliteConnectionFactory factory = initializedFactory()) {
            Connection connection = factory.openConnection();
            connection.close();
            connection.close();

            assertThat(connection.isClosed()).isTrue();
            assertThatThrownBy(connection::createStatement)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("returned");

            try (Connection next = factory.openConnection()) {
                assertThat(queryLong(next, "PRAGMA foreign_keys")).isEqualTo(1);
            }
        }
    }

    @Test
    void concurrentUnitsOfWorkLoseNothingAndStayWithinThePool() throws Exception {
        int workers = SqliteConnectionFactory.MAXIMUM_POOLED_CONNECTIONS * 2;
        int roundsPerWorker = 20;
        try (SqliteStoreFoundation foundation = SqliteStoreFoundation.initialize(
                        new SqliteStoreConfiguration(
                                directory.resolve("runtime.db").toAbsolutePath(), 10_000, 8_192),
                        SqliteTestSupport.CLOCK);
                ExecutorService executor = Executors.newFixedThreadPool(workers)) {
            SqliteRuntimeUnitOfWork unitOfWork = foundation.unitOfWork();
            createProbeTable(foundation.connections());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> results = new ArrayList<>();
            for (int worker = 0; worker < workers; worker++) {
                results.add(executor.submit(() -> {
                    start.await();
                    for (int round = 0; round < roundsPerWorker; round++) {
                        unitOfWork.execute(
                                () -> execute(unitOfWork.currentConnection(), "INSERT INTO probe(note) VALUES ('w')"));
                        unitOfWork.executeReadOnly(() -> count(unitOfWork.currentConnection()));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> result : results) {
                result.get(60, TimeUnit.SECONDS);
            }

            assertThat(unitOfWork.executeReadOnly(() -> count(unitOfWork.currentConnection())))
                    .isEqualTo((long) workers * roundsPerWorker);
            long openedUnderLoad = foundation.connections().physicalConnectionsOpened();
            assertThat(openedUnderLoad).isBetween(1L, (long) SqliteConnectionFactory.MAXIMUM_POOLED_CONNECTIONS);

            for (int round = 0; round < 20; round++) {
                unitOfWork.executeReadOnly(() -> count(unitOfWork.currentConnection()));
            }
            assertThat(foundation.connections().physicalConnectionsOpened()).isEqualTo(openedUnderLoad);
        }
    }

    @Test
    void exhaustedPoolFailsAfterABoundedWaitAndRecoversWhenAConnectionReturns() throws Exception {
        try (SqliteConnectionFactory factory = initializedFactory()) {
            List<Connection> held = new ArrayList<>();
            for (int index = 0; index < SqliteConnectionFactory.MAXIMUM_POOLED_CONNECTIONS; index++) {
                held.add(factory.openConnection());
            }

            long started = System.nanoTime();
            assertThatThrownBy(factory::openConnection)
                    .isInstanceOf(SqliteStoreException.class)
                    .extracting(exception -> ((SqliteStoreException) exception).failure())
                    .isEqualTo(SqliteStoreFailure.DATABASE_BUSY);
            long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            int busyTimeoutMillis = factory.configuration().busyTimeoutMillis();
            assertThat(waitedMillis).isBetween((long) busyTimeoutMillis - 100, busyTimeoutMillis + 5_000L);

            held.remove(0).close();
            try (Connection recovered = factory.openConnection()) {
                assertThat(queryLong(recovered, "PRAGMA foreign_keys")).isEqualTo(1);
            }
            for (Connection connection : held) {
                connection.close();
            }
        }
    }

    @Test
    void longHeldConnectionKeepsItsTransactionWhileOthersCycleThroughTheRestOfThePool() throws Exception {
        try (SqliteConnectionFactory factory = initializedFactory()) {
            createProbeTable(factory);
            try (Connection longHeld = factory.openConnection()) {
                execute(longHeld, "BEGIN");
                assertThat(count(longHeld)).isZero();

                for (int round = 0; round < 200; round++) {
                    try (Connection other = factory.openConnection()) {
                        execute(other, "INSERT INTO probe(note) VALUES ('other')");
                    }
                }

                // Snapshot isolation of the still-open read transaction proves it was never taken away.
                assertThat(count(longHeld)).isZero();
                execute(longHeld, "COMMIT");
                assertThat(count(longHeld)).isEqualTo(200);
            }
        }
    }

    @Test
    void interruptedThreadCanStillPersistAndKeepsItsInterrupt() throws Exception {
        try (SqliteStoreFoundation foundation =
                SqliteStoreFoundation.initialize(SqliteTestSupport.configuration(directory), SqliteTestSupport.CLOCK)) {
            SqliteRuntimeUnitOfWork unitOfWork = foundation.unitOfWork();
            createProbeTable(foundation.connections());

            Thread.currentThread().interrupt();
            try {
                unitOfWork.execute(
                        () -> execute(unitOfWork.currentConnection(), "INSERT INTO probe(note) VALUES ('cancelled')"));
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
                assertThat(unitOfWork.executeReadOnly(() -> count(unitOfWork.currentConnection())))
                        .isEqualTo(1);
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void closingTheFactoryReleasesTheDatabaseFileAndInvalidatesOutstandingLeases() throws Exception {
        SqliteStoreConfiguration configuration = SqliteTestSupport.configuration(directory);
        SqliteConnectionFactory factory = new SqliteConnectionFactory(configuration);
        factory.initialize();
        createProbeTable(factory);
        Connection outstanding = factory.openConnection();
        try (Connection returned = factory.openConnection()) {
            execute(returned, "INSERT INTO probe(note) VALUES ('w')");
        }

        factory.close();
        factory.close();

        assertThatThrownBy(() -> execute(outstanding, "SELECT 1")).isInstanceOf(Exception.class);
        outstanding.close();
        assertThatThrownBy(factory::openConnection)
                .isInstanceOf(SqliteStoreException.class)
                .hasMessageContaining("closed");
        Files.delete(configuration.databasePath());
        assertThat(configuration.databasePath()).doesNotExist();
    }

    private SqliteConnectionFactory initializedFactory() {
        SqliteConnectionFactory factory = new SqliteConnectionFactory(SqliteTestSupport.configuration(directory));
        factory.initialize();
        return factory;
    }

    private static void createProbeTable(SqliteConnectionFactory factory) throws SQLException {
        try (Connection connection = factory.openConnection()) {
            execute(connection, "CREATE TABLE probe (id INTEGER PRIMARY KEY, note TEXT NOT NULL)");
        }
    }

    private static Void execute(Connection connection, String sql) {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
            return null;
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static long count(Connection connection) {
        return queryLong(connection, "SELECT count(*) FROM probe");
    }

    private static long queryLong(Connection connection, String sql) {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
