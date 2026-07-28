package io.haifa.agent.sdk.contribution;

import io.haifa.agent.sdk.product.ProductCapabilityId;
import io.haifa.agent.sdk.product.ProductContributionCoordinate;
import io.haifa.agent.sdk.product.ProductProviderSuitability;
import java.util.Objects;

/** Non-secret identity and compatibility metadata shared by typed contributions. */
public record SdkContributionMetadata(
        ProductContributionCoordinate coordinate,
        ProductCapabilityId capabilityId,
        String configurationDigest,
        ProductProviderSuitability suitability,
        String publicSummary) {
    public SdkContributionMetadata {
        coordinate = Objects.requireNonNull(coordinate, "coordinate must not be null");
        capabilityId = Objects.requireNonNull(capabilityId, "capabilityId must not be null");
        configurationDigest = requireText(configurationDigest, "configurationDigest");
        if (!configurationDigest.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("configurationDigest must be a lowercase SHA-256 digest");
        }
        suitability = Objects.requireNonNull(suitability, "suitability must not be null");
        publicSummary = requireText(publicSummary, "publicSummary");
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
