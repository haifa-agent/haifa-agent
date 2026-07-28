package io.haifa.agent.sdk.product;

public record ProductVersion(String value) {
    public ProductVersion {
        value = ProductValues.text(value, "value", 64);
    }
}
