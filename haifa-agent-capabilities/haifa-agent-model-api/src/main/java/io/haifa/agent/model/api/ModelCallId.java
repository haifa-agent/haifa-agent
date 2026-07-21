package io.haifa.agent.model.api;

/** Identity of one physical request attempt to a model provider. */
public record ModelCallId(String value) {
    public ModelCallId {
        value = ModelValues.text(value, "value");
    }

    @Override
    public String toString() {
        return value;
    }
}
