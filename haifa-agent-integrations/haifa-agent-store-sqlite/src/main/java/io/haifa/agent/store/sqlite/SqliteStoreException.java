package io.haifa.agent.store.sqlite;

import java.util.Objects;

public final class SqliteStoreException extends RuntimeException {
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
