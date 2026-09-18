package io.haifa.agent.store.sqlite;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

public final class SqliteTestSupport {
    public static final Instant NOW = Instant.parse("2026-07-25T08:00:00Z");
    public static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private SqliteTestSupport() {}

    public static SqliteStoreConfiguration configuration(Path directory) {
        return new SqliteStoreConfiguration(directory.resolve("runtime.db").toAbsolutePath(), 1_250, 8_192);
    }

    public static SqliteStoreFoundation foundation(Path directory) {
        return SqliteStoreFoundation.initialize(configuration(directory), CLOCK);
    }
}
