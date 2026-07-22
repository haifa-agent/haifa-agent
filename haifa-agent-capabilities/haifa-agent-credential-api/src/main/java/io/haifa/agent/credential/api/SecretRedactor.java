package io.haifa.agent.credential.api;

public interface SecretRedactor {
    default void track(CredentialLease lease) {}

    default void forget(CredentialLease lease) {}

    String redact(String value);
}
