package io.haifa.agent.credential.api;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record CredentialDefinition(
        CredentialDefinitionId id,
        String providerId,
        CredentialType type,
        Set<String> allowedScopes,
        Set<CredentialExposureMode> allowedExposureModes,
        Map<String, String> metadata) {
    public CredentialDefinition {
        Objects.requireNonNull(id, "id");
        providerId = CredentialValues.text(providerId, "providerId");
        Objects.requireNonNull(type, "type");
        allowedScopes = CredentialValues.set(allowedScopes, "allowedScopes");
        allowedExposureModes = CredentialValues.set(allowedExposureModes, "allowedExposureModes");
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
    }
}
