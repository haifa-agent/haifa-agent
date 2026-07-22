package io.haifa.agent.credential.api;

public interface CredentialBroker {
    CredentialLease issue(CredentialRequest request);

    CredentialLease issue(CredentialOperationRequest request);

    SecretRedactor redactor();
}
