package io.haifa.agent.credential.core;

import io.haifa.agent.credential.api.CredentialBroker;
import io.haifa.agent.credential.api.SecretRedactor;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class DefaultCredentialBroker implements CredentialBroker {
    private final Map<String, String> secrets;
    private final SecretRedactor redactor;

    public DefaultCredentialBroker(Map<String, String> secrets) {
        this(secrets, new DefaultSecretRedactor(secrets.values()));
    }

    public DefaultCredentialBroker(Map<String, String> secrets, SecretRedactor redactor) {
        this.secrets = Map.copyOf(Objects.requireNonNull(secrets, "secrets"));
        this.redactor = Objects.requireNonNull(redactor, "redactor");
    }

    @Override
    public Optional<String> getSecret(String credentialId) {
        Objects.requireNonNull(credentialId, "credentialId");
        return Optional.ofNullable(secrets.get(credentialId));
    }

    @Override
    public SecretRedactor redactor() {
        return redactor;
    }
}
