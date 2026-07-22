package io.haifa.agent.credential.api;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record CredentialBinding(
        String bindingId,
        TenantRef tenant,
        Optional<PrincipalRef> principal,
        CredentialDefinitionId definitionId,
        CredentialReference reference,
        CredentialBindingScope scope,
        Set<String> allowedToolCoordinates,
        Set<String> allowedPurposes,
        Set<String> allowedScopes,
        Set<CredentialExposureMode> allowedExposureModes,
        CredentialStatus status,
        Optional<Instant> expiresAt) {
    public CredentialBinding {
        bindingId = CredentialValues.text(bindingId, "bindingId");
        Objects.requireNonNull(tenant, "tenant");
        principal = Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(definitionId, "definitionId");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(scope, "scope");
        allowedToolCoordinates = CredentialValues.set(allowedToolCoordinates, "allowedToolCoordinates");
        allowedPurposes = CredentialValues.set(allowedPurposes, "allowedPurposes");
        allowedScopes = CredentialValues.set(allowedScopes, "allowedScopes");
        allowedExposureModes = CredentialValues.set(allowedExposureModes, "allowedExposureModes");
        Objects.requireNonNull(status, "status");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
