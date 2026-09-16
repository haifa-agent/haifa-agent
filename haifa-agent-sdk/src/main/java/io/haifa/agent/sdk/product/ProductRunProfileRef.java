package io.haifa.agent.sdk.product;

/** Single reference to the product's default Run Profile. */
public record ProductRunProfileRef(String id, String version) {
    public ProductRunProfileRef {
        id = ProductValues.text(id, "id", 128);
        version = ProductValues.text(version, "version", 64);
    }
}
