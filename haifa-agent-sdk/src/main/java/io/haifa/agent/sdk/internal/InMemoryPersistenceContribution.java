package io.haifa.agent.sdk.internal;

import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
import io.haifa.agent.sdk.contribution.AbstractSdkContribution;
import io.haifa.agent.sdk.contribution.SdkContributionMetadata;
import io.haifa.agent.sdk.product.ProductCapabilities;
import io.haifa.agent.sdk.spi.SdkPersistenceContribution;
import java.util.Objects;

/** Process-local test/development persistence contribution. */
public final class InMemoryPersistenceContribution extends AbstractSdkContribution
        implements SdkPersistenceContribution {
    private final RuntimePersistencePorts runtimePersistence;

    public InMemoryPersistenceContribution(SdkContributionMetadata metadata) {
        this(metadata, RuntimePersistencePorts.inMemory());
    }

    public InMemoryPersistenceContribution(
            SdkContributionMetadata metadata, RuntimePersistencePorts runtimePersistence) {
        super(metadata);
        if (!ProductCapabilities.PERSISTENCE.equals(metadata.capabilityId())) {
            throw new IllegalArgumentException("persistence contribution must provide the persistence capability");
        }
        this.runtimePersistence = Objects.requireNonNull(runtimePersistence, "runtimePersistence must not be null");
    }

    @Override
    public RuntimePersistencePorts runtimePersistence() {
        return runtimePersistence;
    }
}
