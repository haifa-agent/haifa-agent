package io.haifa.agent.sdk.product;

public record ProductId(String value) {
    public ProductId {
        value = ProductValues.text(value, "value", 128);
    }
}
