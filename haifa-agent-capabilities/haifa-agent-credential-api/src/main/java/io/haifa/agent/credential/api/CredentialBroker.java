package io.haifa.agent.credential.api;

public interface CredentialBroker {
    CredentialLease issue(CredentialRequest request);

    SecretRedactor redactor();
}
