package io.haifa.agent.sdk.product;

public record ProductCapabilityId(String value) implements Comparable<ProductCapabilityId> {
    public ProductCapabilityId {
        value = ProductValues.text(value, "value", 128);
    }

    @Override
    public int compareTo(ProductCapabilityId other) {
        return value.compareTo(other.value);
    }
}
