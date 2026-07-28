package io.haifa.agent.sdk.product;

/** Trusted typed capability implementation registered by the host application at startup. */
public interface ProductContribution extends AutoCloseable {
    ProductContributionCoordinate coordinate();

    ProductCapabilityId capabilityId();

    String configurationDigest();

    ProductProviderSuitability suitability();

    String publicSummary();

    default void validate() {}

    /**
     * Acquires resources owned by this assembly.
     *
     * <p>The SDK invokes this exactly once after deterministic resolution and before building the
     * Runtime. A contribution is owned by the SDK only after this method returns successfully.
     */
    default void initialize() {}

    @Override
    default void close() {}
}
