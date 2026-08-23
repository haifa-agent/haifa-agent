package io.haifa.agent.store.sqlite;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

final class SqliteWorkflowStoreTestSupport {
    static final Instant NOW = Instant.parse("2026-07-25T08:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private SqliteWorkflowStoreTestSupport() {}

    static SqliteStoreConfiguration configuration(Path directory) {
        return new SqliteStoreConfiguration(directory.resolve("runtime.db").toAbsolutePath(), 1_250, 8_192);
    }

    static SqliteWorkflowStoreFoundation foundation(Path directory) {
        return SqliteWorkflowStoreFoundation.initialize(configuration(directory), CLOCK);
    }
}
