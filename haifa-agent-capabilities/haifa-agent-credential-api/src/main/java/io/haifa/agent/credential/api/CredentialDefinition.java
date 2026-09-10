package io.haifa.agent.credential.api;

import java.util.Objects;
import java.util.Set;

public record CredentialDefinition(
        CredentialDefinitionId id, Set<String> allowedScopes, Set<CredentialExposureMode> allowedExposureModes) {
    public CredentialDefinition {
        Objects.requireNonNull(id, "id");
        allowedScopes = CredentialValues.set(allowedScopes, "allowedScopes");
        allowedExposureModes = CredentialValues.set(allowedExposureModes, "allowedExposureModes");
    }
}
