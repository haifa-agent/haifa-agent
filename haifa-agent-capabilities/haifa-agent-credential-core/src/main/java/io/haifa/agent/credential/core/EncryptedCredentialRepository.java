package io.haifa.agent.credential.core;

import io.haifa.agent.credential.api.CredentialReference;
import java.util.Optional;

public interface EncryptedCredentialRepository {
    void save(CredentialReference reference, EncryptedCredentialPayload payload);

    Optional<EncryptedCredentialPayload> find(CredentialReference reference);
}
