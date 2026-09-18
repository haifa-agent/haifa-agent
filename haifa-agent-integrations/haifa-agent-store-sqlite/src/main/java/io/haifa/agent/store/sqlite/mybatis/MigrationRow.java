package io.haifa.agent.store.sqlite.mybatis;

import java.time.Instant;

public record MigrationRow(long version, String name, String checksum, Instant appliedAt) {}
