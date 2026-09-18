package io.haifa.agent.store.sqlite;

import io.haifa.agent.runtime.core.model.continuation.ModelContinuationProtector;
import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
import io.haifa.agent.sdk.spi.SdkPersistenceContribution;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public final class SqliteSdkPersistenceContribution implements SdkPersistenceContribution {
    private final SqliteStoreFoundation foundation;
    private final RuntimePersistencePorts ports;
    private final AtomicBoolean closed = new AtomicBoolean();

    public SqliteSdkPersistenceContribution(SqliteStoreFoundation foundation, ModelContinuationProtector protector) {
        this.foundation = Objects.requireNonNull(foundation, "foundation must not be null");
        this.ports = foundation.persistencePorts(Objects.requireNonNull(protector, "protector must not be null"));
    }

    @Override
    public RuntimePersistencePorts runtimePersistence() {
        return ports;
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        try {
            return ports.unitOfWork().execute(work);
        } catch (SqliteStoreException exception) {
            if (exception.getCause() instanceof IllegalStateException conflict) {
                throw conflict;
            }
            throw exception;
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) foundation.close();
    }
}
