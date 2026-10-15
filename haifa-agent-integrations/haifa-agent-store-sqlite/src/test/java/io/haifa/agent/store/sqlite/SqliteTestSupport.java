package io.haifa.agent.store.sqlite;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

public final class SqliteTestSupport {
    public static final Instant NOW = Instant.parse("2026-07-25T08:00:00Z");
    public static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final ThreadLocal<List<AutoCloseable>> OPENED_STORES = ThreadLocal.withInitial(ArrayList::new);

    private SqliteTestSupport() {}

    public static SqliteStoreConfiguration configuration(Path directory) {
        return new SqliteStoreConfiguration(directory.resolve("runtime.db").toAbsolutePath(), 1_250, 8_192);
    }

    public static SqliteStoreFoundation foundation(Path directory) {
        return closeAfterTest(SqliteStoreFoundation.initialize(configuration(directory), CLOCK));
    }

    /**
     * Registers a store that the test does not close itself. An open store keeps pooled connections,
     * and therefore the database file, open until it is closed.
     */
    public static <T extends AutoCloseable> T closeAfterTest(T store) {
        OPENED_STORES.get().add(store);
        return store;
    }

    /** Closes the stores registered on this thread; call it from an {@code @AfterEach} method. */
    public static void closeOpenedStores() throws Exception {
        List<AutoCloseable> stores = OPENED_STORES.get();
        OPENED_STORES.remove();
        Exception failure = null;
        for (AutoCloseable store : stores) {
            try {
                store.close();
            } catch (Exception exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
