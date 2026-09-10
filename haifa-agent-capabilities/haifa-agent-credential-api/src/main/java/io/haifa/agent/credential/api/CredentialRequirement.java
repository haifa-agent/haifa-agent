package io.haifa.agent.credential.api;

public record CredentialRequirement(String credentialId) {
    public CredentialRequirement {
        credentialId = CredentialValues.text(credentialId, "credentialId");
    }
}
