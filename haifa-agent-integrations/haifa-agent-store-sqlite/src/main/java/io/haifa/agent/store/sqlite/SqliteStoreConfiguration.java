package io.haifa.agent.store.sqlite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public record SqliteStoreConfiguration(Path databasePath, int busyTimeoutMillis, int maximumPayloadBytes) {
    public static final int DEFAULT_BUSY_TIMEOUT_MILLIS = 5_000;
    public static final int DEFAULT_MAXIMUM_PAYLOAD_BYTES = 1_048_576;

    public SqliteStoreConfiguration {
        databasePath = Objects.requireNonNull(databasePath, "databasePath must not be null");
        if (!databasePath.isAbsolute()) {
            throw invalid("databasePath must be absolute");
        }
        databasePath = databasePath.normalize();
        Path parent = databasePath.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw invalid("databasePath parent must be an existing directory");
        }
        if (!Files.isWritable(parent)) {
            throw invalid("databasePath parent must be writable");
        }
        if (Files.exists(databasePath) && !Files.isRegularFile(databasePath)) {
            throw invalid("databasePath must identify a regular file");
        }
        if (busyTimeoutMillis < 1) {
            throw invalid("busyTimeoutMillis must be positive");
        }
        if (maximumPayloadBytes < 1) {
            throw invalid("maximumPayloadBytes must be positive");
        }
    }

    public static SqliteStoreConfiguration defaults(Path databasePath) {
        return new SqliteStoreConfiguration(databasePath, DEFAULT_BUSY_TIMEOUT_MILLIS, DEFAULT_MAXIMUM_PAYLOAD_BYTES);
    }

    private static SqliteStoreException invalid(String message) {
        return new SqliteStoreException(SqliteStoreFailure.INVALID_CONFIGURATION, message);
    }
}
