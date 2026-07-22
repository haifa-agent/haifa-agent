package io.haifa.agent.credential.api;

import java.util.Objects;
import java.util.Set;

public record CredentialRequirement(
        CredentialDefinitionId definitionId, String purpose, Set<String> scopes, CredentialExposureMode exposureMode) {
    public CredentialRequirement {
        Objects.requireNonNull(definitionId, "definitionId");
        purpose = CredentialValues.text(purpose, "purpose");
        scopes = CredentialValues.set(scopes, "scopes");
        Objects.requireNonNull(exposureMode, "exposureMode");
    }
}
