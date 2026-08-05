package io.haifa.agent.sdk.contribution;

import io.haifa.agent.sdk.internal.InMemoryPersistenceContribution;
import io.haifa.agent.sdk.product.ProductContribution;
import java.util.Objects;

/** Public factories for SDK-owned capability contributions with safe, narrow signatures. */
public final class SdkContributions {
    private SdkContributions() {}

    /**
     * Creates process-local Runtime persistence for development and tests.
     *
     * <p>All state is discarded when the process exits. Production applications must use a
     * durable persistence provider.
     */
    public static ProductContribution inMemoryPersistence(SdkContributionMetadata metadata) {
        return new InMemoryPersistenceContribution(Objects.requireNonNull(metadata, "metadata must not be null"));
    }
}
