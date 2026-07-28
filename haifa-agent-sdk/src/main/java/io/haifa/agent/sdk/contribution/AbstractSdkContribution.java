package io.haifa.agent.sdk.contribution;

import io.haifa.agent.sdk.product.ProductCapabilityId;
import io.haifa.agent.sdk.product.ProductContribution;
import io.haifa.agent.sdk.product.ProductContributionCoordinate;
import io.haifa.agent.sdk.product.ProductProviderSuitability;
import java.util.Objects;

/** Base implementation that keeps typed contribution metadata consistent. */
public abstract class AbstractSdkContribution implements ProductContribution {
    private final SdkContributionMetadata metadata;

    protected AbstractSdkContribution(SdkContributionMetadata metadata) {
        this.metadata = Objects.requireNonNull(metadata, "metadata must not be null");
    }

    @Override
    public final ProductContributionCoordinate coordinate() {
        return metadata.coordinate();
    }

    @Override
    public final ProductCapabilityId capabilityId() {
        return metadata.capabilityId();
    }

    @Override
    public final String configurationDigest() {
        return metadata.configurationDigest();
    }

    @Override
    public final ProductProviderSuitability suitability() {
        return metadata.suitability();
    }

    @Override
    public final String publicSummary() {
        return metadata.publicSummary();
    }
}
