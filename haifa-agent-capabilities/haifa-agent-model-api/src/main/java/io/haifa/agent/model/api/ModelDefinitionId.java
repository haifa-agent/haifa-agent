package io.haifa.agent.model.api;

/** Stable Haifa identity for a model definition, independent from a provider model alias. */
public record ModelDefinitionId(String value) {
    public ModelDefinitionId {
        value = ModelValues.text(value, "value");
    }

    @Override
    public String toString() {
        return value;
    }
}
