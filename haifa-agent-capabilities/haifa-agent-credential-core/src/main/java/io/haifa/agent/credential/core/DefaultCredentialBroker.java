package io.haifa.agent.credential.core;

import io.haifa.agent.credential.api.CredentialBroker;
import io.haifa.agent.credential.api.SecretRedactor;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

public final class DefaultCredentialBroker implements CredentialBroker {
    private final Function<String, Optional<String>> secretSupplier;
    private final SecretRedactor redactor;

    /**
     * Creates a credential broker using a dynamic secret supplier.
     * Secrets are resolved on demand and dynamically registered to the default redactor.
     */
    public DefaultCredentialBroker(Function<String, Optional<String>> secretSupplier) {
        this(secretSupplier, new DefaultSecretRedactor());
    }

    /**
     * Creates a credential broker using a dynamic secret supplier and a custom redactor.
     */
    public DefaultCredentialBroker(Function<String, Optional<String>> secretSupplier, SecretRedactor redactor) {
        this.secretSupplier = Objects.requireNonNull(secretSupplier, "secretSupplier");
        this.redactor = Objects.requireNonNull(redactor, "redactor");
    }

    /**
     * Lightweight constructor intended primarily for testing or scenarios with pre-resolved secrets.
     */
    public DefaultCredentialBroker(Map<String, String> secrets) {
        this(secrets, new DefaultSecretRedactor(secrets.values()));
    }

    /**
     * Lightweight constructor intended primarily for testing with a custom redactor.
     */
    public DefaultCredentialBroker(Map<String, String> secrets, SecretRedactor redactor) {
        Objects.requireNonNull(secrets, "secrets");
        Objects.requireNonNull(redactor, "redactor");
        Map<String, String> snapshot = Map.copyOf(secrets);
        this.secretSupplier = id -> Optional.ofNullable(snapshot.get(id));
        this.redactor = redactor;
    }

    @Override
    public Optional<String> getSecret(String credentialId) {
        Objects.requireNonNull(credentialId, "credentialId");
        Optional<String> secret = secretSupplier.apply(credentialId);
        secret.ifPresent(s -> {
            if (redactor instanceof DefaultSecretRedactor defaultRedactor) {
                defaultRedactor.registerSecret(s);
            }
        });
        return secret;
    }

    @Override
    public SecretRedactor redactor() {
        return redactor;
    }
}
