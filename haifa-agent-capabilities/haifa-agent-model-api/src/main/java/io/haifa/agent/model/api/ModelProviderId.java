package io.haifa.agent.model.api;

/** Stable Haifa identity for a configured model provider. */
public record ModelProviderId(String value) {
    public ModelProviderId {
        value = ModelValues.text(value, "value");
    }

    @Override
    public String toString() {
        return value;
    }
}
