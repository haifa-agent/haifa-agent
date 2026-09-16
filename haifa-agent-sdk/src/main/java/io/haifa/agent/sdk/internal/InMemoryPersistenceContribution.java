package io.haifa.agent.sdk.internal;

import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
import io.haifa.agent.sdk.spi.SdkPersistenceContribution;
import java.util.Objects;

/** Internal process-local persistence implementation. */
public final class InMemoryPersistenceContribution implements SdkPersistenceContribution {
    private final RuntimePersistencePorts runtimePersistence;

    public InMemoryPersistenceContribution() {
        this(RuntimePersistencePorts.inMemory());
    }

    public InMemoryPersistenceContribution(RuntimePersistencePorts runtimePersistence) {
        this.runtimePersistence = Objects.requireNonNull(runtimePersistence, "runtimePersistence must not be null");
    }

    @Override
    public RuntimePersistencePorts runtimePersistence() {
        return runtimePersistence;
    }
}
