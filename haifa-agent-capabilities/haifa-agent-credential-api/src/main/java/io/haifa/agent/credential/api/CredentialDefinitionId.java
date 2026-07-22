package io.haifa.agent.credential.api;

public record CredentialDefinitionId(String value) {
    public CredentialDefinitionId {
        value = CredentialValues.text(value, "value");
    }
}
