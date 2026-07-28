package io.haifa.agent.sdk.product;

import java.util.Objects;

public record ProductContributionCoordinate(String providerId, String version)
        implements Comparable<ProductContributionCoordinate> {
    public ProductContributionCoordinate {
        providerId = ProductValues.text(providerId, "providerId", 128);
        version = ProductValues.text(version, "version", 64);
    }

    public String externalForm() {
        return providerId + "@" + version;
    }

    @Override
    public int compareTo(ProductContributionCoordinate other) {
        Objects.requireNonNull(other, "other must not be null");
        int providerComparison = providerId.compareTo(other.providerId);
        return providerComparison == 0 ? version.compareTo(other.version) : providerComparison;
    }
}
