package io.haifa.agent.contract.common;

public record IdempotencyKey(String value) {
    public IdempotencyKey {
        value = CorrelationId.requireText(value, "value", 256);
    }
}
