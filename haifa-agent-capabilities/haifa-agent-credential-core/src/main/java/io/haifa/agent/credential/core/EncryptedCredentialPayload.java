package io.haifa.agent.credential.core;

import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.credential.api.CredentialDefinitionId;
import java.util.Objects;

public record EncryptedCredentialPayload(
        TenantRef tenant, CredentialDefinitionId definitionId, byte[] nonce, byte[] ciphertext) {
    public EncryptedCredentialPayload {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(definitionId, "definitionId");
        nonce = Objects.requireNonNull(nonce, "nonce").clone();
        ciphertext = Objects.requireNonNull(ciphertext, "ciphertext").clone();
    }

    @Override
    public byte[] nonce() {
        return nonce.clone();
    }

    @Override
    public byte[] ciphertext() {
        return ciphertext.clone();
    }
}
