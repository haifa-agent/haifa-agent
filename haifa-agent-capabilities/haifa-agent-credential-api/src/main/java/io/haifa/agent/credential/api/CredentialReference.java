package io.haifa.agent.credential.api;

public record CredentialReference(String value) {
    public CredentialReference {
        value = CredentialValues.text(value, "value");
    }
}
