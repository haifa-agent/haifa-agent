package io.haifa.agent.sdk.product;

import java.util.Objects;
import java.util.Set;

public record ProductCapabilityRequirement(
        ProductCapabilityId capabilityId,
        ProductCapabilityMode mode,
        Set<ProductContributionCoordinate> allowedContributions,
        ProductProviderSuitability minimumSuitability) {

    public ProductCapabilityRequirement {
        capabilityId = Objects.requireNonNull(capabilityId, "capabilityId must not be null");
        mode = Objects.requireNonNull(mode, "mode must not be null");
        allowedContributions =
                Set.copyOf(Objects.requireNonNull(allowedContributions, "allowedContributions must not be null"));
        minimumSuitability = Objects.requireNonNull(minimumSuitability, "minimumSuitability must not be null");
        if (mode == ProductCapabilityMode.NONE && !allowedContributions.isEmpty()) {
            throw new IllegalArgumentException("NONE capability cannot allow contributions");
        }
    }

    public static ProductCapabilityRequirement none(ProductCapabilityId id) {
        return new ProductCapabilityRequirement(
                id, ProductCapabilityMode.NONE, Set.of(), ProductProviderSuitability.TEST_ONLY);
    }

    public static ProductCapabilityRequirement optional(
            ProductCapabilityId id, Set<ProductContributionCoordinate> allowed) {
        return new ProductCapabilityRequirement(
                id, ProductCapabilityMode.OPTIONAL, allowed, ProductProviderSuitability.TEST_ONLY);
    }

    public static ProductCapabilityRequirement required(
            ProductCapabilityId id,
            Set<ProductContributionCoordinate> allowed,
            ProductProviderSuitability minimumSuitability) {
        return new ProductCapabilityRequirement(id, ProductCapabilityMode.REQUIRED, allowed, minimumSuitability);
    }
}
