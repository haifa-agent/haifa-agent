package io.haifa.agent.store.sqlite;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

public record SqliteStoreConfiguration(
        Path databasePath, int busyTimeoutMillis, int maximumPayloadBytes, int writerQueueTimeoutMillis) {
    public static final int DEFAULT_BUSY_TIMEOUT_MILLIS = 5_000;
    public static final int DEFAULT_MAXIMUM_PAYLOAD_BYTES = 1_048_576;
    /**
     * Default fallback bound for waiting in the in-process writer queue. SQLite's {@code busy_timeout} bounds
     * cross-process lock contention, so it does not bound this queue: the queue is fair, and a settled position
     * guarantees progress. The bound only ends the wait of a caller whose holder leaked the writer slot, so it is
     * deliberately far above any unit of work that a healthy process runs.
     */
    public static final int DEFAULT_WRITER_QUEUE_TIMEOUT_MILLIS = 60_000;

    public SqliteStoreConfiguration(Path databasePath, int busyTimeoutMillis, int maximumPayloadBytes) {
        this(databasePath, busyTimeoutMillis, maximumPayloadBytes, DEFAULT_WRITER_QUEUE_TIMEOUT_MILLIS);
    }

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
        if (Files.exists(databasePath, LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(databasePath)
                        || !Files.isRegularFile(databasePath, LinkOption.NOFOLLOW_LINKS))) {
            throw invalid("databasePath must identify a regular file");
        }
        if (busyTimeoutMillis < 1) {
            throw invalid("busyTimeoutMillis must be positive");
        }
        if (maximumPayloadBytes < 1) {
            throw invalid("maximumPayloadBytes must be positive");
        }
        if (writerQueueTimeoutMillis < 1) {
            throw invalid("writerQueueTimeoutMillis must be positive");
        }
    }

    public static SqliteStoreConfiguration defaults(Path databasePath) {
        return new SqliteStoreConfiguration(
                databasePath,
                DEFAULT_BUSY_TIMEOUT_MILLIS,
                DEFAULT_MAXIMUM_PAYLOAD_BYTES,
                DEFAULT_WRITER_QUEUE_TIMEOUT_MILLIS);
    }

    private static SqliteStoreException invalid(String message) {
        return new SqliteStoreException(SqliteStoreFailure.INVALID_CONFIGURATION, message);
    }
}
