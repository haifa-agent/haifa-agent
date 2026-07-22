package io.haifa.agent.credential.core;

import io.haifa.agent.credential.api.CredentialReference;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryEncryptedCredentialRepository implements EncryptedCredentialRepository {
    private final Map<CredentialReference, EncryptedCredentialPayload> payloads = new ConcurrentHashMap<>();

    @Override
    public void save(CredentialReference reference, EncryptedCredentialPayload payload) {
        payloads.put(reference, payload);
    }

    @Override
    public Optional<EncryptedCredentialPayload> find(CredentialReference reference) {
        return Optional.ofNullable(payloads.get(reference));
    }
}
