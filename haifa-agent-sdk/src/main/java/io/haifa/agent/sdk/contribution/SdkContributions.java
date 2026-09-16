package io.haifa.agent.sdk.contribution;

import io.haifa.agent.sdk.internal.InMemoryPersistenceContribution;
import io.haifa.agent.sdk.spi.SdkPersistenceContribution;

/** Public factories for SDK-owned components with safe, narrow signatures. */
public final class SdkContributions {
    private SdkContributions() {}

    /**
     * Creates process-local Runtime persistence for development and tests.
     *
     * <p>All state is discarded when the process exits. Production applications must use a
     * durable persistence provider.
     */
    public static SdkPersistenceContribution inMemoryPersistence() {
        return new InMemoryPersistenceContribution();
    }
}
