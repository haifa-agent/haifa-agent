package io.haifa.agent.model.api;

/** Non-secret reference resolved only at the provider boundary. */
public record CredentialRef(String value) {
    public CredentialRef {
        value = ModelValues.text(value, "value");
    }

    @Override
    public String toString() {
        return value;
    }
}
