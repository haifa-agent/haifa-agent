package io.haifa.agent.store.sqlite;

import io.haifa.agent.runtime.core.model.continuation.ModelContinuationProtector;
import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
import io.haifa.agent.sdk.contribution.AbstractSdkContribution;
import io.haifa.agent.sdk.contribution.SdkContributionMetadata;
import io.haifa.agent.sdk.product.ProductCapabilities;
import io.haifa.agent.sdk.spi.SdkPersistenceContribution;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SqliteSdkPersistenceContribution extends AbstractSdkContribution
        implements SdkPersistenceContribution {
    private final SqliteStoreFoundation foundation;
    private final RuntimePersistencePorts ports;
    private final AtomicBoolean closed = new AtomicBoolean();

    public SqliteSdkPersistenceContribution(
            SdkContributionMetadata metadata, SqliteStoreFoundation foundation, ModelContinuationProtector protector) {
        super(metadata);
        if (!ProductCapabilities.PERSISTENCE.equals(metadata.capabilityId())) {
            throw new IllegalArgumentException("SQLite persistence must provide the persistence capability");
        }
        this.foundation = Objects.requireNonNull(foundation, "foundation must not be null");
        this.ports = foundation.persistencePorts(Objects.requireNonNull(protector, "protector must not be null"));
    }

    @Override
    public RuntimePersistencePorts runtimePersistence() {
        return ports;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) foundation.close();
    }
}
