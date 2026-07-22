package io.haifa.agent.credential.api;

import io.haifa.agent.core.reference.TenantRef;
import java.time.Instant;

public interface CredentialStore {
    void store(CredentialReference reference, TenantRef tenant, CredentialDefinitionId definitionId, byte[] secret);

    CredentialLease lease(
            CredentialReference reference, TenantRef tenant, CredentialDefinitionId definitionId, Instant expiresAt);
}
