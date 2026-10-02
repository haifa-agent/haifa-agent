package io.haifa.agent.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reports the average cost of one outermost unit of work so a change to connection handling can be
 * compared before and after on the same machine. The numbers are printed, not asserted.
 */
class SqliteUnitOfWorkTimingTest {
    private static final int ROUNDS = 200;

    @TempDir
    Path directory;

    @Test
    void reportsAverageUnitOfWorkCost() throws Exception {
        try (SqliteStoreFoundation foundation = SqliteTestSupport.foundation(directory)) {
            SqliteRuntimeUnitOfWork unitOfWork = foundation.unitOfWork();
            unitOfWork.execute(() -> {
                try (Statement statement = unitOfWork.currentConnection().createStatement()) {
                    statement.execute("CREATE TABLE timing_probe (id INTEGER PRIMARY KEY, payload TEXT)");
                } catch (java.sql.SQLException exception) {
                    throw new IllegalStateException(exception);
                }
                return null;
            });

            long writeStarted = System.nanoTime();
            for (int round = 0; round < ROUNDS; round++) {
                unitOfWork.execute(() -> {
                    try (Statement statement = unitOfWork.currentConnection().createStatement()) {
                        statement.execute("INSERT INTO timing_probe(payload) VALUES ('probe')");
                    } catch (java.sql.SQLException exception) {
                        throw new IllegalStateException(exception);
                    }
                    return null;
                });
            }
            double writeMillis = (System.nanoTime() - writeStarted) / 1_000_000.0 / ROUNDS;

            long readStarted = System.nanoTime();
            long rows = 0;
            for (int round = 0; round < ROUNDS; round++) {
                rows = unitOfWork.executeReadOnly(() -> {
                    try (Statement statement = unitOfWork.currentConnection().createStatement();
                            var result = statement.executeQuery("SELECT count(*) FROM timing_probe")) {
                        result.next();
                        return result.getLong(1);
                    } catch (java.sql.SQLException exception) {
                        throw new IllegalStateException(exception);
                    }
                });
            }
            double readMillis = (System.nanoTime() - readStarted) / 1_000_000.0 / ROUNDS;

            assertThat(rows).isEqualTo(ROUNDS);
            System.out.printf(
                    "SQLITE_UOW_TIMING rounds=%d writeMsPerUnitOfWork=%.2f readMsPerUnitOfWork=%.2f%n",
                    ROUNDS, writeMillis, readMillis);
        }
    }
}
