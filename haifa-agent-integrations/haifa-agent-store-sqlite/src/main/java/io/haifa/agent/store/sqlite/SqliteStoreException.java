package io.haifa.agent.store.sqlite;

import io.haifa.agent.runtime.core.storage.StorageException;
import java.util.Objects;

/** Store failure; Runtime Core classifies it as {@code STORAGE_UNAVAILABLE} rather than a model or tool failure. */
public final class SqliteStoreException extends StorageException {
    private final SqliteStoreFailure failure;

    public SqliteStoreException(SqliteStoreFailure failure, String message) {
        super(message);
        this.failure = Objects.requireNonNull(failure, "failure must not be null");
    }

    public SqliteStoreException(SqliteStoreFailure failure, String message, Throwable cause) {
        super(message, cause);
        this.failure = Objects.requireNonNull(failure, "failure must not be null");
    }

    public SqliteStoreFailure failure() {
        return failure;
    }
}
