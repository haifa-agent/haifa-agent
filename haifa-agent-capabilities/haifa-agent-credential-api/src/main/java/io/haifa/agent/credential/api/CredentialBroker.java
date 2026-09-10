package io.haifa.agent.credential.api;

import java.util.Objects;
import java.util.Optional;

public interface CredentialBroker {
    Optional<String> getSecret(String credentialId);

    default String requireSecret(String credentialId) {
        Objects.requireNonNull(credentialId, "credentialId");
        return getSecret(credentialId)
                .orElseThrow(() -> new CredentialException("credential unavailable: " + credentialId));
    }

    default SecretRedactor redactor() {
        return SecretRedactor.noop();
    }
}
