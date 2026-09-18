package io.haifa.agent.model.api;

/** Stable identity for one frozen logical request across bounded physical attempts. */
public record ModelRequestId(String value) {
    public ModelRequestId {
        value = ModelValues.text(value, "value");
    }
}
