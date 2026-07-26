package io.haifa.agent.store.sqlite;

import java.util.function.Supplier;

final class SqlitePolicyStoreSupport {
    private SqlitePolicyStoreSupport() {}

    static <T> T execute(SqliteRuntimeUnitOfWork unitOfWork, Supplier<T> work) {
        try {
            return unitOfWork.execute(work);
        } catch (SqliteStoreException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) throw runtimeException;
            throw exception;
        }
    }
}
