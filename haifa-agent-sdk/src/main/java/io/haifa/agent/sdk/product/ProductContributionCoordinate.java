package io.haifa.agent.sdk.product;

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
        return externalForm().compareTo(other.externalForm());
    }
}
