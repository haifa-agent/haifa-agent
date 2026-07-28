package io.haifa.agent.sdk.product;

import java.util.Objects;

public record ResolvedProductContribution(
        ProductCapabilityId capabilityId,
        ProductContributionCoordinate coordinate,
        String configurationDigest,
        ProductProviderSuitability suitability,
        String publicSummary) {

    public ResolvedProductContribution {
        capabilityId = Objects.requireNonNull(capabilityId, "capabilityId must not be null");
        coordinate = Objects.requireNonNull(coordinate, "coordinate must not be null");
        configurationDigest = ProductValues.requireDigest(configurationDigest, "configurationDigest");
        suitability = Objects.requireNonNull(suitability, "suitability must not be null");
        publicSummary = ProductValues.text(publicSummary, "publicSummary", 512);
    }

    public static ResolvedProductContribution from(ProductContribution contribution) {
        return new ResolvedProductContribution(
                contribution.capabilityId(),
                contribution.coordinate(),
                contribution.configurationDigest(),
                contribution.suitability(),
                contribution.publicSummary());
    }
}
